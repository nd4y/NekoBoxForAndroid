# nekoctl

Python 3.9+ client for this fork's ADB Control API. Standard library only.

1. Enable **Settings > Misc > ADB Control API** in the installed NekoBox fork.
2. Authorize your computer for ADB and obtain the intended device serial from `adb devices`.
3. Always specify the target serial. The default package is `com.nb4a.plus.debug`.

```powershell
python nekoctl.py --serial emulator-5580 status
python nekoctl.py --serial emulator-5580 profiles
python nekoctl.py --serial emulator-5580 rules
python nekoctl.py --serial emulator-5580 settings
python nekoctl.py --serial emulator-5580 --output config.json export
python nekoctl.py --serial emulator-5580 plan example-patch.json
python nekoctl.py --serial emulator-5580 apply example-patch.json --revision REVISION_FROM_PLAN
python nekoctl.py --serial emulator-5580 vpn restart
```

On Windows `nekoctl.cmd` invokes the same script. Use `--adb` for the full path to adb, `--package` for another installed package, or `ANDROID_SERIAL` instead of repeating `--serial`.

The example IDs are placeholders: inspect `profiles` and edit the example before applying it. Secrets omitted from a patch stay unchanged. Export redacts secrets unless `--include-secrets` is explicitly provided. Save/apply never restarts VPN automatically. Read the full API documentation in `API.md` in the distribution ZIP, or [docs/ADB_CONTROL.md](../../docs/ADB_CONTROL.md) in the repository.

Tests: `python -m unittest test_nekoctl.py -v`. `smoke_emulator.py` and `lifecycle_emulator.py` perform integration tests, modify synthetic emulator fixtures, and restore configuration; both refuse physical-device serials. They are not normal setup commands.

For complete LLM editing, use `pull config.json`, edit the document, `validate config.json`, then `push config.json`. `import` aliases `push`; `push --dry-run` aliases validation. Complete pulls include credentials and must stay private. The document carries its base revision, so push detects intervening edits. Android authorization stays local, and saving never implicitly restarts VPN. See API.md for the full document schema and recovery behavior. `render --profile ID` provides a redacted generated native configuration for diagnostics.
