# WikiSync
Pushes up bits of data about your player to a server hosted by the wiki.

The varbits/varplayers passed up to the server are determined by the manifest returned by the server.
This allows us to handle updates quickly without having to push a change to the plugin itself.
Your player's stats are passed up to the server.
For details about what is passed to the server, see the [wiki page](https://oldschool.runescape.wiki/w/RuneScape:WikiSync).

Keep in mind that this data is public, similar to the official HiScores.

## Third-party use of the API
The WikiSync plugin and the associated API is intended for use by the wiki,
and not by third-party developers. While we love seeing what cool projects people
can make with data from the wiki, people who enable the plugin are choosing
to share their game data to improve their experience on the wiki, rather than
arbitrary third-parties. **Please do not use the WikiSync API in your own projects**.
We will be actively taking steps to limit the usage of our API outside of our own websites.