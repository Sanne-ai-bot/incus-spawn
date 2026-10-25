"""Which methods of the given classes a PGO profile counts calls for.

The profile's applicability says how much of an image it covers, not which code: this answers
whether a path is in the profile at all, by listing each method of a class (lambdas included,
as the compiler names them) with the calls the profile counted into it, and the ones it never saw.
A method missing from the list has no profile and is compiled as cold code.

Usage: python3 -I bench/pgo-coverage.py <profile.iprof[.gz]> <class> [<name-substring>]
  e.g. python3 -I bench/pgo-coverage.py proxy.iprof.gz dev.incusspawn.proxy.ArtifactCacheHandler Npm
"""
import gzip, json, sys


def load(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt") as f:
        return json.load(f)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    profile, cls = load(sys.argv[1]), sys.argv[2]
    needle = sys.argv[3].lower() if len(sys.argv) > 3 else ""
    types = {t["id"]: t["name"] for t in profile["types"]}
    methods = {m["id"]: m for m in profile["methods"]
               if types.get(m["signature"][0]) == cls and needle in m["name"].lower()}
    calls = {}
    # A context is "<method>:<bci><<caller>:<bci>...": the first frame is the profiled method
    for p in profile["callCountProfiles"]:
        method = int(p["ctx"].split(":", 1)[0])
        if method in methods:
            calls[method] = calls.get(method, 0) + sum(p["records"])

    def label(m):
        # An overload is told apart by its parameter types (the signature after its class and return type)
        name = methods[m]["name"]
        overloads = [o for o in methods.values() if o["name"] == name]
        if len(overloads) == 1:
            return name
        return name + "(" + ", ".join(types.get(t, "?").rsplit(".", 1)[-1] for t in methods[m]["signature"][2:]) + ")"

    counted = sorted(((calls[m], label(m)) for m in calls), reverse=True)
    print(f"{cls}{' (' + sys.argv[3] + ')' if needle else ''}: {len(counted)} of {len(methods)} methods counted")
    for n, name in counted:
        print(f"  {n:>12,}  {name}")
    unseen = sorted(label(m) for m in methods if m not in calls)
    if unseen:
        print("  never counted: " + ", ".join(unseen))


if __name__ == "__main__":
    main()
