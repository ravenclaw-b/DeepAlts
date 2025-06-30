# 🌐 DeepAlts

A Paper plugin for detecting alternate accounts based on IP history, with both shallow and deep scanning modes.

## What It Does

DeepAlts helps server staff find potential alternate accounts (alts) by checking which players have logged in from the same IP address, even indirectly through a shared chain of connections.

* `/alts <player|UUID> [uuid]`: Lists accounts that have used the same **last known IP** as the target.
  
* `/deepalts <player|UUID> [uuid]`: Finds all players connected through **any shared IPs** across login history using a BFS search.

> Add `uuid` as the second argument to force UUID output instead of usernames.

## Features

* Async lookups — won't lag your server
* Supports both player names and UUIDs
* Optional UUID-only output for logging
* Tab completion for player names
* Gracefully handles players who have never joined
* Permission-based usage (`deepalts.use`)

## 🔧 Commands

| Command              | Description     |                                                 |
| -------------------- | --------------- | ----------------------------------------------- |
| \/alts \<player or uuid> \[uuid] | Shows players with the same **last IP**         |
| \/deepalts \<player or uuid> \[uuid] | Finds players with **any shared IP** connections |

## 🔐 Permissions

| Node           | Description                    |
| -------------- | ------------------------------ |
| `deepalts.use` | Required to use either command |

## Requirements

* PaperMC 1.20+
* Java 17+

## Installation

1. Download the `.jar` from Modrinth or GitHub.
2. Drop it into your `plugins/` folder.
3. Restart or reload your server.
