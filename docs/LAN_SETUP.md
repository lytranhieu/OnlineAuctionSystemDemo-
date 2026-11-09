# LAN/Wi-Fi deployment — Online Auction System

This patch keeps Java 21, Gson, JavaFX, TCP port 5050 and existing newline-delimited JSON/Packet format. It does **not** install encryption. Use only on a trusted private LAN; never port-forward TCP 5050 to the Internet.

## Start Server (Windows)

From the project root after `mvnw.cmd clean verify` and `mvnw.cmd package`:

```powershell
$env:AUCTION_ADMIN_PASSWORD = "<your-own-private-password>"  # only if bootstrapping admin
java -jar .\auction-server\target\auction-server-1.0.0-all.jar --bind=0.0.0.0 --port=5050 --data=data/state.json
```

`run-server.bat` has the same network arguments. Existing syntax `AuctionServer 5050 data/state.json` remains supported. To listen only on one interface use `--bind=192.168.1.10`. Restrict network access through the OS firewall.

## Determine Server LAN address

- Windows: `ipconfig` -> active Wi-Fi/Ethernet adapter -> `IPv4 Address`, for example `192.168.1.10`.
- macOS: System Settings -> Network -> Wi-Fi/Ethernet -> Details -> TCP/IP; or `ipconfig getifaddr en0` where `en0` is your active adapter.
- Linux: `ip -4 addr` or `hostname -I`; use the active LAN IPv4, not `127.0.0.1`.

**TỰ ĐỘNG:** Client giờ tìm Server bằng UDP 5051, không cần điền IP/port. Khởi động Server trước, sau đó mở Client trong cùng broadcast LAN; nếu Client mở trước hãy nhấn **Tìm lại**. Chi tiết tại [`AUTO_DISCOVERY_LAN.md`](AUTO_DISCOVERY_LAN.md).

## Windows Defender Firewall — private network only

On Server Windows, run PowerShell *as Administrator*:

```powershell
New-NetFirewallRule -DisplayName "Online Auction TCP 5050 LAN" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5050 -Profile Private -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName "Auction UDP 5051 Discovery" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5051 -Profile Private -RemoteAddress LocalSubnet
```

Review/disable the rule when no longer needed:

```powershell
Get-NetFirewallRule -DisplayName "Online Auction TCP 5050 LAN","Auction UDP 5051 Discovery"
Disable-NetFirewallRule -DisplayName "Online Auction TCP 5050 LAN","Auction UDP 5051 Discovery"
```

For macOS, use System Settings -> Network -> Firewall -> Options to allow inbound Java applications (if enabled). For Linux UFW, prefer only your own subnet, e.g. `sudo ufw allow from 192.168.1.0/24 to any port 5050 proto tcp`; adjust subnet to match your actual LAN (do not blindly paste an unrelated subnet).

## Network tests

With Server running, on **a different Windows computer**:

```powershell
Test-NetConnection 192.168.1.10 -Port 5050
```

`TcpTestSucceeded: True` confirms TCP reachability, not that Java login/auth succeeds. On macOS/Linux use `nc -vz 192.168.1.10 5050`. On Server, check the listener using `Get-NetTCPConnection -LocalPort 5050 -State Listen` or `netstat -ano | findstr :5050`.

`Connection refused`: wrong IP/port, Server not running, or not listening on LAN. `Timed out`: firewall, guest Wi-Fi/AP/client isolation, VPN, wrong subnet or routing. `Address already in use`: another process occupies 5050. Failed login after TCP succeeds: check Server logs and that the same Server-side `data/state.json` is used; accounts are stored on Server.

## End-to-end acceptance

1. Run Server on computer A and note its active LAN IPv4.
2. From computer B connect to A:5050 using Client GUI and log in as Seller; create an auction.
3. From computer C log in as Admin and approve the auction.
4. From computer D/B log in as Bidder; place a valid bid. Observe price change on other Clients without clicking Refresh.
5. Race-test two Bidders bidding at the same price. Exactly one request must succeed; the other must get a validation error. Verify final history and winning bidder.
6. Disconnect Wi-Fi or stop Server on one Client: GUI should report disconnection and return to login, automatically discovering the available Server again.
7. Stop Server using Ctrl+C; existing peers should be closed and port released.

## Limitations and security

The protocol transmits passwords in plaintext *over the network* (JSON over TCP; hashes are stored on disk). This is suitable only as a classroom demo on a trusted private network. For deployment outside a trusted LAN, add TLS (`SSLSocket`) or an authenticated encrypted gateway, plus proper certificate validation. Use a dedicated Windows **Private** firewall profile and LocalSubnet scope; **do not** forward port 5050 on a router.
