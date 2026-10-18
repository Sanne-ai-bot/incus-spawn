Recorded on GitHub's `macos-15` runner (macOS 15.7.9, arm64) by the #1515 probe, after:

```
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain root.pem
sudo security add-trusted-cert -d -r trustRoot -p ssl -k ... ssl.pem
sudo security add-trusted-cert -d -r trustRoot -p smime -k ... smime.pem
sudo security add-trusted-cert -d -r deny -k ... deny.pem
sudo security add-certificates -k ... notrust.pem
sudo security add-trusted-cert -d -r trustRoot -p ssl -s probe.example -k ... host.pem
security trust-settings-export -d admin-domain.plist
```

`security verify-cert -p ssl` then accepted `root` and `ssl` only.
The certificates are throwaway CAs whose keys were deleted on the runner.
