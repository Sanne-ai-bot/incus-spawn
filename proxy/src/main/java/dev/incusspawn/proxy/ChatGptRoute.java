package dev.incusspawn.proxy;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountShape;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketConnectOptions;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A ChatGPT session's requests from Codex in an instance (#1332): the real session stays on the
 * host ({@link ChatGptSessionStore}), the guest holds proof-bearing placeholders
 * ({@link ChatGptProof}, {@link ChatGptPlaceholders}), and this is where one becomes the other.
 *
 * <h3>Which requests</h3>
 * Only requests that <em>claim</em> to be isx's -- an isx access placeholder as the bearer, or an
 * isx refresh placeholder in a token request -- are handled here; everything else to these hosts is
 * relayed untouched, so a browser or a user's own {@code codex login} sees a transparent proxy.
 * A claimed request then has to pass, in this order, each failure answered here and never relayed:
 * <ol>
 *   <li>its shape: one {@code Authorization}, one {@code Host} that is the TLS name, no encoded or
 *       dot segments, nothing that could make the allowlist and upstream read a path differently
 *       (400);</li>
 *   <li>the allowlist, {@link Operation}: method and exact path, nothing else (403);</li>
 *   <li>the caller: the instance at the source address, bound to the account it is served, and
 *       the proof it presents matching that binding's verifier (401);</li>
 *   <li>the session: still the one the binding was made for, so a sign-in again invalidates every
 *       proof made before it, and a refresh never upgrades one (401).</li>
 * </ol>
 *
 * <h3>Refresh</h3>
 * Codex refreshes when its access token is about to expire or a request was refused. The guest's
 * request is relayed with the real refresh token in place of the placeholder, only when the real
 * token needs it -- within {@link #REFRESH_WINDOW_SECONDS} of expiry, or after a 401 from the
 * backend -- and otherwise answered here from the current session. Concurrent refreshes of one
 * session share one upstream call. The result is committed only to the session it started from
 * ({@link ChatGptSessionStore#commit}); one the account signed in again or was removed since is
 * discarded, its new refresh token revoked, and the request fails.
 *
 * <p>At most one refresh of a session goes upstream per {@link #MIN_UPSTREAM_INTERVAL_MILLIS}, so no
 * guest can turn its requests into a stream of refreshes at OpenAI.
 *
 * <p>This is an experimental compatibility mechanism built from Codex's source: it shows that isx
 * works with the tested client, not that OpenAI supports the integration.
 */
final class ChatGptRoute {

    static final String CHATGPT = "chatgpt.com";
    static final String AUTH = "auth.openai.com";
    static final Set<String> DOMAINS = Set.of(CHATGPT, AUTH);
    static final String NAMESPACE = "openai";
    /** Codex's OAuth client id (codex-rs {@code CLIENT_ID}): a refresh naming another is not Codex's. */
    static final String CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    /** Codex refreshes 5 minutes before expiry; the real token is refreshed in the same window. */
    static final long REFRESH_WINDOW_SECONDS = 300;
    /**
     * At most one refresh of a session goes upstream in this long; any other is answered here. A
     * guest asks whenever its token looks close to expiry, and a short-lived real token (or one the
     * backend keeps refusing) would otherwise turn every request into a refresh at OpenAI.
     */
    static final long MIN_UPSTREAM_INTERVAL_MILLIS = 30_000;
    private static final int MAX_AUTH_BODY = 64 * 1024;
    /** Codex sends whole conversations; anything past this is not a request isx relays with a credential. */
    static final long MAX_BACKEND_BODY = 64L * 1024 * 1024;
    private static final String HINT = " (isx, #1332)";

    /** What a claimed request may do. Nothing else is relayed with a credential. */
    enum Operation {
        RESPONSES(CHATGPT, HttpMethod.POST, "/backend-api/codex/responses", null),
        RESPONSES_WEBSOCKET(CHATGPT, HttpMethod.GET, "/backend-api/codex/responses", null),
        MODELS(CHATGPT, HttpMethod.GET, "/backend-api/codex/models", Pattern.compile("client_version=[0-9A-Za-z.+-]{1,64}")),
        REFRESH(AUTH, HttpMethod.POST, "/oauth/token", null),
        REVOKE(AUTH, HttpMethod.POST, "/oauth/revoke", null);

        final String host;
        final HttpMethod method;
        final String path;
        /** The whole query this operation may carry, or null for none. */
        final Pattern query;

        Operation(String host, HttpMethod method, String path, Pattern query) {
            this.host = host;
            this.method = method;
            this.path = path;
            this.query = query;
        }

        /**
         * The operation a request target names, or null. {@code target} has already passed
         * {@link #safeTarget}, so plain string comparison is what upstream will see too.
         */
        static Operation of(String host, HttpMethod method, String target, boolean webSocket) {
            var q = target.indexOf('?');
            var path = q < 0 ? target : target.substring(0, q);
            var query = q < 0 ? null : target.substring(q + 1);
            for (var op : values()) {
                if (!op.host.equals(host) || !op.method.equals(method) || !op.path.equals(path)) continue;
                if ((op == RESPONSES_WEBSOCKET) != webSocket) continue;
                if (query == null || (op.query != null && op.query.matcher(query).matches())) return op;
            }
            return null;
        }
    }

    /**
     * Whether a request target is safe to match against the allowlist as plain text: origin-form,
     * only unreserved characters, no percent-encoding, no empty or dot segments. Anything a server
     * might decode or normalise into another path is refused, never normalised here.
     */
    static boolean safeTarget(String target) {
        if (target == null || target.isEmpty() || target.charAt(0) != '/') return false;
        if (!SAFE_TARGET.matcher(target).matches()) return false;
        var q = target.indexOf('?');
        var path = q < 0 ? target : target.substring(0, q);
        for (var segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static final Pattern SAFE_TARGET = Pattern.compile("[A-Za-z0-9/._~+=&?-]{1,512}");
    /** Headers a claimed request may carry at most once: a second copy could be read either way. */
    private static final List<String> SINGLE_HEADERS = List.of("Authorization", "ChatGPT-Account-Id", "Host",
            "Content-Length", "Transfer-Encoding");

    private final MitmProxy proxy;
    private final Vertx vertx;
    private final ChatGptSessionStore store;
    /** One upstream refresh per session and generation at a time. */
    private final Map<String, Future<Refreshed>> refreshes = new ConcurrentHashMap<>();
    /**
     * The tokens the backend refused ({@link #tokenKey}): their session may refresh now. Keyed by
     * generation, so a late 401 for a token a refresh has already replaced marks nothing current.
     */
    private final Set<String> refused = ConcurrentHashMap.newKeySet();
    /** When each session last refreshed upstream, for {@link #MIN_UPSTREAM_INTERVAL_MILLIS}. */
    private final Map<String, Long> lastUpstream = new ConcurrentHashMap<>();
    /** Refusals already logged: each operation off the allowlist, each caller's wrong proof, once. */
    private final Set<String> loggedRefusals = ConcurrentHashMap.newKeySet();

    /** {@link #MAX_BACKEND_BODY}; lowered by tests that check the bound by sending past it. */
    volatile long maxBackendBody = MAX_BACKEND_BODY;

    private volatile JqValue configTree = JqValues.parse("{}");
    private volatile AccountShape shape = AccountShape.UNDECLARED;
    private volatile boolean enabled;

    ChatGptRoute(MitmProxy proxy, Vertx vertx, ChatGptSessionStore store) {
        this.proxy = proxy;
        this.vertx = vertx;
        this.store = store;
    }

    /** Takes on a config: the route is active only while some {@code openai} account is a ChatGPT one. */
    void useConfig(JqValue tree, AccountShape openaiShape) {
        configTree = tree;
        shape = openaiShape == null ? AccountShape.UNDECLARED : openaiShape;
        enabled = enabledBy(tree);
    }

    /** Whether {@code tree} configures any {@code openai} account of {@code type: chatgpt}. */
    static boolean enabledBy(JqValue tree) {
        if (!(tree.path(NAMESPACE).path("accounts") instanceof JqObject accounts)) return false;
        var any = new boolean[1];
        accounts.forEach((name, account) -> { if (isChatGpt(account)) any[0] = true; });
        return any[0];
    }

    private static boolean isChatGpt(JqValue account) {
        return "chatgpt".equals(account.pathText("type", ""));
    }

    boolean enabled() {
        return enabled;
    }

    boolean handles(String domain) {
        return enabled && DOMAINS.contains(domain);
    }

    // --- HTTP ---

    void handle(HttpServerRequest req, String domain, String sourceAddress) {
        if (domain.equals(AUTH) && req.method() == HttpMethod.POST
                && (Operation.REFRESH.path.equals(req.path()) || Operation.REVOKE.path.equals(req.path()))) {
            // The claim is in the body: read it, bounded, before deciding. A body too large to be
            // a token request is nobody's placeholder, and streams through untouched.
            if (declaredLength(req) > MAX_AUTH_BODY) {
                proxy.upstream.relayRequest(req, domain);
                return;
            }
            readBounded(req, MAX_AUTH_BODY).onSuccess(body -> {
                if (!body.toString().contains(ChatGptProof.MARKER)) {
                    proxy.upstream.relayBuffered(req, domain, body, upReq -> {}, upResp -> {});
                    return;
                }
                var gate = gate(req, domain, sourceAddress, false);
                if (gate == null) return;
                if (gate.op() == Operation.REFRESH) {
                    refresh(req, gate.binding(), body, sourceAddress);
                } else {
                    // A guest's 'codex logout' must never end the host's session: answered here
                    ProxyLog.info("Answering a ChatGPT token revocation from " + describe(sourceAddress)
                            + " locally; the host session is untouched");
                    req.response().putHeader("Content-Type", "application/json").end("{}");
                }
            });
            return;
        }
        if (!claims(req.headers())) {
            proxy.upstream.relayRequest(req, domain);
            return;
        }
        // Everything a claimed backend request is judged on is in its head: the body is read only
        // once the caller has proved it may send one, so no instance can make the proxy hold it
        var gate = gate(req, domain, sourceAddress, true);
        if (gate == null) {
            stopReading(req);
            return;
        }
        readBounded(req, maxBackendBody).onSuccess(body -> currentSession(gate.binding()).onComplete(ar -> {
            if (ar.failed() || ar.result() == null) {
                refuseProof(req.response(), gate.op(), sourceAddress, SESSION_CHANGED);
                return;
            }
            forwardBackend(req, ar.result(), body);
        }));
    }

    private static long declaredLength(HttpServerRequest req) {
        try {
            var length = req.getHeader("Content-Length");
            return length == null ? -1 : Long.parseLong(length.strip());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * The request's body, read into memory only up to {@code max} bytes: past that the request is
     * answered 413 and the connection closed, so a guest cannot make the shared proxy hold more.
     * The returned future completes only with a whole body; every failure is answered here.
     */
    private static Future<Buffer> readBounded(HttpServerRequest req, long max) {
        var promise = Promise.<Buffer>promise();
        if (declaredLength(req) > max) {
            tooLarge(req);
            return promise.future();
        }
        var body = Buffer.buffer();
        req.handler(chunk -> {
            if (body.length() + chunk.length() > max) {
                tooLarge(req);
                return;
            }
            body.appendBuffer(chunk);
        });
        req.exceptionHandler(e -> { if (!req.response().ended()) req.response().setStatusCode(400).end(); });
        req.endHandler(v -> { if (!req.response().ended()) promise.tryComplete(body); });
        return promise.future();
    }

    private static void tooLarge(HttpServerRequest req) {
        var resp = req.response();
        if (resp.ended() || resp.closed()) return;
        resp.setStatusCode(413).putHeader("Connection", "close").end();
        stopReading(req);
    }

    /**
     * After an answer that leaves the rest of the body unread: stop reading it. Paused, the
     * connection pushes back on the sender rather than have the proxy drain it, and it is not
     * reused; the client or the idle timeout ends it. Closing it at once instead could reset it
     * before the answer was read, while the client still sends.
     */
    private static void stopReading(HttpServerRequest req) {
        if (!req.isEnded()) req.pause();
    }

    /** Whether a request's headers claim an isx access placeholder. */
    private static boolean claims(MultiMap headers) {
        for (var value : headers.getAll("Authorization")) {
            if (ChatGptProof.claimsAccess(bearer(value))) return true;
        }
        return false;
    }

    /** A claimed request whose head passed every check: its caller may send it a body. */
    private record Gate(Operation op, InstanceRegistry.ChatGptBinding binding) {}

    /**
     * Checks a claimed request's head: its shape (400), the allowlist (403), that its caller is
     * bound to the account it is served (401) and, when {@code proofInHead}, the access proof its
     * {@code Authorization} carries (401). A token request's proof is in its body, which the
     * caller checks once read. Returns null after answering a refusal.
     */
    private Gate gate(HttpServerRequest req, String domain, String sourceAddress, boolean proofInHead) {
        var resp = req.response();
        if (!req.isEnded()) {
            // A refusal leaves the body unread (see stopReading): the connection is not reused
            resp.putHeader("Connection", "close");
        }
        var shapeProblem = shapeProblem(req.headers(), domain, req.uri(), sni(req));
        if (shapeProblem != null) {
            refuse(resp, 400, "invalid_request", shapeProblem);
            return null;
        }
        var op = Operation.of(domain, req.method(), req.uri(), false);
        if (op == null) {
            var what = req.method() + " " + req.path();
            warnOnce("Refusing " + domain + " " + what + " for " + describe(sourceAddress)
                    + ": not an operation ChatGPT sessions allow (#1332)");
            refuse(resp, 403, "forbidden", what + " is not allowed for ChatGPT sessions");
            return null;
        }
        var binding = binding(sourceAddress);
        if (binding == null) {
            refuseProof(resp, op, sourceAddress, "no ChatGPT session is bound to this instance");
            return null;
        }
        if (proofInHead
                && !ChatGptProof.matches(ChatGptProof.accessDigestOf(bearer(req.getHeader("Authorization"))), binding.accessSha256())) {
            refuseProof(resp, op, sourceAddress, "the access proof is not this instance's current one");
            return null;
        }
        resp.headers().remove("Connection");
        return new Gate(op, binding);
    }

    /**
     * Why a claimed request's shape is refused, or null. Upstream must see exactly what the
     * allowlist matched: one of each header that names the credential or frames the body, a Host
     * that is the TLS name, and a target that no server decodes into another path.
     */
    static String shapeProblem(MultiMap headers, String domain, String target, String sni) {
        for (var name : SINGLE_HEADERS) {
            var values = headers.getAll(name);
            if (values.size() > 1 || values.stream().anyMatch(v -> v.contains(","))) {
                return "more than one " + name;
            }
        }
        if (headers.contains("Content-Length") && headers.contains("Transfer-Encoding")) {
            return "both Content-Length and Transfer-Encoding";
        }
        var host = headers.get("Host");
        if (host == null || !(host.equals(domain) || host.equals(domain + ":443"))) return "a Host that is not " + domain;
        // Fails closed: a claimed request with no TLS name is one this check cannot judge
        if (sni == null || !sni.equals(domain)) return "a TLS name that is not " + domain;
        if (!safeTarget(target)) return "a request target isx does not relay with a credential";
        return null;
    }

    private static String sni(HttpServerRequest req) {
        var connection = req.connection();
        return connection == null ? null : connection.indicatedServerName();
    }

    /** The TLS name a WebSocket's handshake asked for, or null when it sent none. */
    private static String sni(ServerWebSocket ws) {
        if (!(ws.sslSession() instanceof ExtendedSSLSession session)) return null;
        for (var name : session.getRequestedServerNames()) {
            if (name instanceof SNIHostName host) return host.getAsciiName();
        }
        return null;
    }

    // --- Backend ---

    private void forwardBackend(HttpServerRequest req, ChatGptSessionStore.Session session, Buffer body) {
        var key = tokenKey(session);
        proxy.upstream.relayBuffered(req, CHATGPT, body, upReq -> {
            upReq.putHeader("Authorization", "Bearer " + session.tokens().accessToken());
            upReq.putHeader("ChatGPT-Account-Id", session.tokens().accountId());
        }, upResp -> { if (upResp.statusCode() == 401) refused.add(key); });
    }

    // --- Refresh ---

    /** A refresh's outcome: the session's tokens after it, or why the caller gets none. */
    private record Refreshed(ChatGptSessionStore.Tokens tokens, String sessionId, int status, String code) {
        static Refreshed ok(ChatGptSessionStore.Session session) {
            return new Refreshed(session.tokens(), session.sessionId(), 200, null);
        }

        static Refreshed refused(int status, String code) {
            return new Refreshed(null, null, status, code);
        }
    }

    private void refresh(HttpServerRequest req, InstanceRegistry.ChatGptBinding binding, Buffer body,
                         String sourceAddress) {
        var resp = req.response();
        JqValue parsed;
        try {
            parsed = JqValues.parseStrict(body.getBytes());
        } catch (RuntimeException e) {
            parsed = null;
        }
        if (!(parsed instanceof JqObject request) || !request.keys().equals(Set.of("client_id", "grant_type", "refresh_token"))
                || !"refresh_token".equals(request.pathText("grant_type", ""))
                || !CODEX_CLIENT_ID.equals(request.pathText("client_id", ""))) {
            // Not the request the tested Codex sends: fail closed rather than guess what it means
            warnOnce("Refusing a ChatGPT token request from " + describe(sourceAddress)
                    + ": not the refresh request isx recognises (#1332)");
            refuse(resp, 400, "invalid_request", "unrecognised token request");
            return;
        }
        var digest = ChatGptProof.refreshDigestOf(request.pathText("refresh_token", ""));
        if (!ChatGptProof.matches(digest, binding.refreshSha256())) {
            refuseProof(resp, Operation.REFRESH, sourceAddress, "the refresh proof is not this instance's current one");
            return;
        }
        var proofs = new ChatGptProof.Proofs(ChatGptProof.accessFromRefresh(digest), digest);
        currentSession(binding).compose(session -> {
            if (session == null) return Future.succeededFuture(Refreshed.refused(401, "invalid_grant"));
            var key = sessionKey(session);
            var expiresIn = ChatGptPlaceholders.expiry(session.tokens().accessToken()) - System.currentTimeMillis() / 1000;
            var tokenRefused = refused.contains(tokenKey(session));
            var due = expiresIn <= REFRESH_WINDOW_SECONDS || tokenRefused;
            var last = lastUpstream.get(key);
            if (!due) return Future.succeededFuture(Refreshed.ok(session));
            if (last != null && System.currentTimeMillis() - last < MIN_UPSTREAM_INTERVAL_MILLIS) {
                // Throttled: a token that still works is answered with. One that has expired, or
                // that the backend has just refused, would only send Codex round a refresh-and-401
                // loop with a bearer known to be bad, so it is told to try again later
                return Future.succeededFuture(expiresIn > 0 && !tokenRefused ? Refreshed.ok(session)
                        : Refreshed.refused(503, "temporarily_unavailable"));
            }
            return refreshes.computeIfAbsent(tokenKey(session), k -> {
                var started = upstreamRefresh(req, session, request);
                started.onComplete(ar -> refreshes.remove(k));
                return started;
            });
        }).onComplete(ar -> {
            if (ar.failed()) {
                ProxyLog.warn("ChatGPT refresh failed: " + ar.cause().getMessage());
                refuse(resp, 502, "server_error", "the refresh could not be completed");
                return;
            }
            var outcome = ar.result();
            // Only for the session this caller's proofs were made for: never upgrade them to another
            if (outcome.status() != 200 || !binding.sessionId().equals(outcome.sessionId())) {
                refuse(resp, outcome.status() == 200 ? 401 : outcome.status(),
                        outcome.code() == null ? "invalid_grant" : outcome.code(),
                        "the ChatGPT session is not the one this instance's proofs were made for, or the refresh was refused");
                return;
            }
            String answer;
            try {
                answer = ChatGptPlaceholders.refreshResponse(outcome.tokens(), proofs);
            } catch (RuntimeException e) {
                // A stored token that is no JWT: say so, rather than leave the guest waiting
                ProxyLog.warn("Cannot answer a ChatGPT refresh for account '" + binding.account() + "': " + e.getMessage());
                refuse(resp, 502, "server_error", "the session's tokens could not be read");
                return;
            }
            resp.putHeader("Content-Type", "application/json").end(answer);
        });
    }

    /** One refresh of {@code session} upstream, with the guest's request as its template. */
    private Future<Refreshed> upstreamRefresh(HttpServerRequest req, ChatGptSessionStore.Session session,
                                              JqObject guestRequest) {
        var key = sessionKey(session);
        return vertx.executeBlocking(() -> store.beginRefresh(session), false).compose(begun -> {
            // Another refresh of the session landed first: answer from it, if it is still this session
            if (!begun) return vertx.executeBlocking(() -> current(session), false);
            lastUpstream.put(key, System.currentTimeMillis());
            // The guest's body, key for key, with the one substitution
            var substituted = JqObject.builder(3);
            guestRequest.forEach((k, v) -> substituted.put(k,
                    k.equals("refresh_token") ? JqString.of(session.tokens().refreshToken()) : v));
            var body = Buffer.buffer(substituted.build().toJsonString());
            var options = new RequestOptions().setMethod(HttpMethod.POST).setHost(AUTH).setPort(443).setURI("/oauth/token");
            return proxy.upstream.requestWithAsyncDns(options).compose(upReq -> {
                proxy.upstream.copyRequestHeaders(req, upReq, AUTH);
                upReq.headers().remove("Authorization");
                // The answer is parsed here, and the upstream client does not decompress
                upReq.putHeader("Accept-Encoding", "identity");
                upReq.putHeader("Content-Type", "application/json");
                upReq.putHeader("Content-Length", String.valueOf(body.length()));
                return upReq.send(body).compose(upResp -> upResp.body().map(b -> Map.entry(upResp.statusCode(), b)))
                        .compose(answer -> vertx.executeBlocking(() -> settle(session, answer.getKey(), answer.getValue()), false),
                                // Sent but its answer lost: OpenAI may have rotated the token. The record of
                                // the attempt stays, and the next refresh says so if it is refused.
                                e -> {
                                    ProxyLog.warn("ChatGPT refresh for account '" + session.account()
                                            + "' got no answer: " + e.getMessage());
                                    return Future.failedFuture(e);
                                });
            }, e -> vertx.executeBlocking(() -> {
                // Never sent: nothing can have rotated, so the attempt is not left on record
                store.abandonRefresh(session);
                return (Refreshed) null;
            }, false).transform(ignored -> Future.failedFuture(e)));
        }).onSuccess(outcome -> {
            // Refreshed: no earlier token of the session matters any more, late 401s for it included
            if (outcome.status() == 200) refused.removeIf(token -> token.startsWith(key + "@"));
        });
    }

    /** Commits or discards what OpenAI answered a refresh of {@code session} with. Blocks. */
    private Refreshed settle(ChatGptSessionStore.Session session, int status, Buffer answer) {
        if (status != 200) {
            store.abandonRefresh(session);
            var code = errorCode(answer);
            ProxyLog.warn("ChatGPT refresh for account '" + session.account() + "' refused upstream: HTTP "
                    + status + (code == null ? "" : " " + code));
            if (status != 400 && status != 401) {
                // Rate limits and server errors say nothing about the token: Codex may retry
                return Refreshed.refused(status == 429 ? 429 : 502, "server_error");
            }
            if (code != null && (code.startsWith("refresh_token_") || code.equals("invalid_grant"))
                    && isDefaultAccount(session.account())) {
                proxy.setAuthError(authErrorPrefix(session.account())
                        + "can no longer be refreshed (" + code + ")"
                        + (session.refreshing() != null ? ", after a refresh that was interrupted at "
                                + session.refreshing() : "") + ". Sign in again.", "isx init");
            }
            return Refreshed.refused(status, code == null ? "invalid_grant" : code);
        }
        JqValue tokens;
        try {
            tokens = JqValues.parseStrict(answer.getBytes());
        } catch (RuntimeException e) {
            tokens = JqValues.parse("{}");
        }
        var refreshed = new ChatGptSessionStore.Tokens(
                tokens.pathText("id_token", session.tokens().idToken()),
                tokens.pathText("access_token", ""),
                tokens.pathText("refresh_token", ""),
                session.tokens().accountId());
        if (refreshed.accessToken().isEmpty() || refreshed.refreshToken().isEmpty()) {
            // OpenAI said yes, so it may have rotated the token: the record of the attempt stays
            ProxyLog.warn("ChatGPT refresh for account '" + session.account() + "' answered without tokens");
            return Refreshed.refused(502, "server_error");
        }
        var commit = store.commit(session, refreshed);
        if (commit != ChatGptSessionStore.Commit.COMMITTED) {
            ProxyLog.warn("Discarding a ChatGPT refresh for account '" + session.account() + "': the session "
                    + (commit == ChatGptSessionStore.Commit.GONE ? "was removed" : "changed") + " while it ran");
            revoke(refreshed.refreshToken());
            return current(session);
        }
        // Only an error this account raised: a working ChatGPT session says nothing about Claude's
        proxy.clearAuthErrorIf(standing -> standing.startsWith(authErrorPrefix(session.account())));
        return new Refreshed(refreshed, session.sessionId(), 200, null);
    }

    /** How an error about {@code account}'s session begins, so that only its own success clears it. */
    private static String authErrorPrefix(String account) {
        return "The ChatGPT session of openai account '" + account + "' ";
    }

    /**
     * Whether {@code account} is the {@code openai} account the host itself is served: like
     * {@code MitmProxy}'s {@code usesDefaultCredentials()}, only that one may raise the host-wide
     * "sign in again". A pinned instance's broken session is reported to that instance.
     */
    private boolean isDefaultAccount(String account) {
        return account.equals(effectiveAccount(null));
    }

    /** The {@code openai} account served for {@code pinned} (null: none), or null when none can be. */
    private String effectiveAccount(String pinned) {
        try {
            return AccountResolver.effectiveAccount(configTree, NAMESPACE, shape, pinned);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Best effort: the refresh token of a chain nobody holds any more. */
    private void revoke(String refreshToken) {
        var body = Buffer.buffer(JqObject.builder(3)
                .put("token", JqString.of(refreshToken))
                .put("token_type_hint", JqString.of("refresh_token"))
                .put("client_id", JqString.of(CODEX_CLIENT_ID))
                .build().toJsonString());
        var options = new RequestOptions().setMethod(HttpMethod.POST).setHost(AUTH).setPort(443).setURI("/oauth/revoke");
        proxy.upstream.requestWithAsyncDns(options)
                .compose(upReq -> upReq.putHeader("Content-Type", "application/json").send(body))
                .onFailure(e -> ProxyLog.warn("Could not revoke a discarded ChatGPT refresh token: " + e.getMessage()));
    }

    private static String errorCode(Buffer answer) {
        try {
            var root = JqValues.parseStrict(answer.getBytes());
            var error = root.path("error");
            var code = error.isString() ? error.asText() : error.pathText("code", "");
            if (code.isEmpty()) code = root.pathText("code", "");
            return code.matches("[A-Za-z0-9_.-]{1,64}") ? code : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- WebSocket ---

    /**
     * A WebSocket to one of these hosts; returns whether it was accepted, as
     * {@code MitmProxy.relayWebSocket} does. Unclaimed sockets go back to the ordinary relay.
     */
    boolean handleWebSocket(ServerWebSocket ws, String domain, String sourceAddress, ApiActivity.Flow flow) {
        if (!claims(ws.headers())) return proxy.relayWebSocketWith(ws, domain, flow, Future.succeededFuture(options -> {}), status -> {});
        var problem = shapeProblem(ws.headers(), domain, ws.uri(), sni(ws));
        if (problem != null) {
            warnOnce("Refusing a ChatGPT WebSocket from " + describe(sourceAddress) + ": " + problem);
            ws.reject(400);
            return false;
        }
        var op = Operation.of(domain, HttpMethod.GET, ws.uri(), true);
        if (op == null) {
            warnOnce("Refusing WebSocket " + domain + ws.path() + " for " + describe(sourceAddress)
                    + ": not an operation ChatGPT sessions allow (#1332)");
            ws.reject(403);
            return false;
        }
        var binding = binding(sourceAddress);
        var digest = ChatGptProof.accessDigestOf(bearer(ws.headers().get("Authorization")));
        if (binding == null || !ChatGptProof.matches(digest, binding.accessSha256())) {
            warnOnce("Refusing a ChatGPT WebSocket from " + describe(sourceAddress) + ": no valid proof");
            ws.reject(401);
            return false;
        }
        var dialled = new java.util.concurrent.atomic.AtomicReference<ChatGptSessionStore.Session>();
        return proxy.relayWebSocketWith(ws, domain, flow, currentSession(binding).map(session -> {
            if (session == null) throw new IllegalStateException(SESSION_CHANGED);
            dialled.set(session);
            return (java.util.function.Consumer<WebSocketConnectOptions>) options -> {
                options.putHeader("Authorization", "Bearer " + session.tokens().accessToken());
                options.putHeader("ChatGPT-Account-Id", session.tokens().accountId());
            };
        }), status -> {
            // Refused like an HTTP request would be: the session may refresh upstream now
            if (status == 401 && dialled.get() != null) refused.add(tokenKey(dialled.get()));
        });
    }

    // --- Shared ---

    /**
     * The caller's binding, if the instance at {@code sourceAddress} is bound to a ChatGPT account
     * that is the {@code openai} account it is served now. Non-blocking.
     */
    private InstanceRegistry.ChatGptBinding binding(String sourceAddress) {
        var registry = proxy.instanceRegistry();
        var binding = registry == null ? null : registry.chatGptBinding(sourceAddress);
        if (binding == null) return null;
        var caller = registry.lookup(sourceAddress);
        if (caller == null || !caller.instanceName().equals(binding.instanceName())) return null;
        var account = effectiveAccount(caller.accountsByNamespace().get(NAMESPACE));
        if (account == null || !account.equals(binding.account())
                || !isChatGpt(configTree.path(NAMESPACE).path("accounts").path(account))) return null;
        return binding;
    }

    /** The account's session, read now, if it is still the one {@code binding} was made for; else null. */
    private Future<ChatGptSessionStore.Session> currentSession(InstanceRegistry.ChatGptBinding binding) {
        return vertx.executeBlocking(() -> {
            var session = store.read(binding.account());
            return session != null && session.sessionId().equals(binding.sessionId()) ? session : null;
        }, false);
    }

    private static final String SESSION_CHANGED =
            "the ChatGPT session changed or is gone; start a new login shell once isx has delivered new credentials";

    /**
     * What a refresh of {@code session} that another write overtook answers with: the account's
     * current tokens if it is still the same session at a later generation, else a refusal. Never
     * another session's: a sign-in again does not upgrade old proofs. Blocks.
     */
    private Refreshed current(ChatGptSessionStore.Session session) {
        var now = store.read(session.account());
        if (now != null && now.sessionId().equals(session.sessionId())) return Refreshed.ok(now);
        // No longer the account's session: drop what is kept about it
        refused.removeIf(token -> token.startsWith(sessionKey(session) + "@"));
        lastUpstream.remove(sessionKey(session));
        return Refreshed.refused(401, "invalid_grant");
    }

    private static String sessionKey(ChatGptSessionStore.Session session) {
        return session.account() + ":" + session.sessionId();
    }

    /** One token of a session: the session at one generation. */
    private static String tokenKey(ChatGptSessionStore.Session session) {
        return sessionKey(session) + "@" + session.generation();
    }

    private void warnOnce(String message) {
        // Bounded: a guest chooses the paths it is refused for
        if (loggedRefusals.size() > 1024) loggedRefusals.clear();
        if (loggedRefusals.add(message)) ProxyLog.warn(message);
    }

    private void refuseProof(HttpServerResponse resp, Operation op, String sourceAddress, String why) {
        // Once per caller and reason: a guest can send wrong proofs as fast as it likes
        warnOnce("Refusing a ChatGPT " + op.path + " request from " + describe(sourceAddress) + ": " + why);
        refuse(resp, 401, op == Operation.REFRESH ? "invalid_grant" : "unauthorized", why);
    }

    /** An answer in the shape OpenAI's errors take, so Codex classifies it as it would theirs. */
    private static void refuse(HttpServerResponse resp, int status, String code, String message) {
        if (resp.ended() || resp.closed()) return;
        resp.setStatusCode(status).putHeader("Content-Type", "application/json")
                .end(JqObject.builder(1).put("error", JqObject.builder(2)
                        .put("code", JqString.of(code))
                        .put("message", JqString.of(message + HINT)).build()).build().toJsonString());
    }

    private String describe(String sourceAddress) {
        var registry = proxy.instanceRegistry();
        var instance = registry == null ? null : registry.lookup(sourceAddress);
        return instance != null ? "instance '" + instance.instanceName() + "'"
                : sourceAddress == null ? "an unknown caller" : "caller " + sourceAddress;
    }

    private static String bearer(String authorization) {
        if (authorization == null) return null;
        return authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7).strip() : null;
    }
}
