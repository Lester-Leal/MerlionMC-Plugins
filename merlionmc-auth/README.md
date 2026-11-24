<p align="center"><img src="branding/icon-256.png" alt="MerlionMCAuth" width="160"></p>

# MerlionMCAuth

Login and registration for Paper servers that run with online mode off, made by MerlionMC Host.

Players set a password the first time they join (`/register`) and type it each time after (`/login`), so nobody can
play under someone else's name. Until they log in they can't move, chat, use items or see where the real player is.
Bedrock players joining through Geyser + Floodgate are already verified by Xbox and skip it.

- Passwords hashed with Argon2id, never shown or logged
- Limits on wrong passwords that slow attackers down without locking real players out
- One-time codes from the server owner after a password reset, and for players who were here before the plugin
- Works with the MerlionMC dashboard: see and reset login accounts from the Players tab

Requires Paper 1.20+ (built and tested on 26.3) and Java 25. Bouncy Castle is downloaded by Paper on first start.

## Commands

| Command | Who |
|---|---|
| `/register <password> <password>` | Players (with `<code>` first when the owner gave one) |
| `/login <password>` | Players |
| `/changepassword <current> <new>` | Logged-in players |
| `/mmauth list`, `/mmauth reset <player>`, `/mmauth code <player>` | Server console and RCON only |
