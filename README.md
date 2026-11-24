# MerlionMC Plugins

Minecraft server plugins made by [MerlionMC Host](https://merlionmchost.xyz), free Minecraft server hosting for
Southeast Asia. Each folder is one plugin with its own build.

| Plugin | What it does | Download |
|---|---|---|
| [MerlionMCAuth](merlionmc-auth) | Login and registration for offline-mode Paper servers | [Modrinth](https://modrinth.com/project/merlionmcauth) |

## Building

Each plugin builds on its own with the included Gradle wrapper (Java 25):

```sh
cd merlionmc-auth
./gradlew build        # the jar lands in build/libs
./gradlew runServer    # a local Paper test server with the plugin
```

## License

GPL-3.0, see [LICENSE](LICENSE). You may use, change and share these plugins; if you share a changed version,
share its source under the same license.

## Security

Found a security problem? Please don't open a public issue: tell us privately on
[Discord](https://discord.gg/wZ5YCbbCm7) or at support@merlionmchost.xyz.

Not affiliated with Mojang or Microsoft.
