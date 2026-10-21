# WikiSync
Pushes up bits of data about your player to a server hosted by the wiki.

The varbits/varplayers passed up to the server are determined by the manifest returned by the server.
This allows us to handle updates quickly without having to push a change to the plugin itself.
Your player's stats are passed up to the server.
For details about what is passed to the server, see the [wiki page](https://oldschool.runescape.wiki/w/RuneScape:WikiSync).

Keep in mind that this data is public, similar to the official HiScores.

The optional local WebSocket API also supports [owned-item requests](LOCAL-OWNED-ITEMS.md)
for browser tools. Bank sharing defaults off; these snapshots stay local and are
not included in the public WikiSync uploads described above.
