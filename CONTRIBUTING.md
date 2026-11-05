# Contributing to Visor

Thank you for helping. Visor is small on purpose, and these rules keep it that way.

## Before you start

- For a question, ask in [Discussions](https://github.com/macedonga/visor/discussions/categories/q-a).
- For a bug, open an issue with the bug form. Include the event log from the app (Settings, Advanced, Event log, Share).
- For anything bigger than a fix, open an issue first and say what you want to change and why. A pull request that nobody asked for may wait a long time.
- Read [CLAUDE.md](CLAUDE.md). Its protocol traps each cost days to find.

## The glasses are the test

There are no test suites. A change is done when it works on real glasses.

In your pull request, say what you tried and on what: the glasses and their firmware, the phone and its Android version, and what you did and saw. For a protocol change, attach the part of `wire.log` that shows it working. A debug build writes it to `files/wire.log`.

Remove serial numbers, Bluetooth addresses, tokens and API keys from every log you share.

## Rules for the code

- Keep it small. Short comments that say why, not what. No planning documents.
- No backend, no analytics, no crash reporting. Visor talks to Meta only where the glasses leave no other way.
- Make the glasses do the work. Implement what the glasses expect, and do not fake a glasses feature on the phone, such as phone speech in place of the glasses' own announcements.
- Do not get software that Meta did not sign past the glasses' signature checks.
- Dependency versions live only in `gradle/libs.versions.toml`. Use the latest stable release that is at least two weeks old. No alphas, betas or release candidates. Add a dependency only when the platform and the existing code cannot do the job.
- Match the code around your change: names, structure, the way errors are handled.
- Text in the app follows the phone's language. Add every new string in English (`values/`), Italian (`values-it/`) and Russian (`values-ru/`).
- A change to a screen follows the requirements in [ACCESSIBILITY.md](ACCESSIBILITY.md).

## Commits and pull requests

- One logical change per commit. The subject is imperative and under about 70 characters. The body, when there is one, says why.
- One topic per pull request. Fill in the template.
- `main` must build: CI runs `./gradlew :app:assembleDebug :app:assembleRelease` on every pull request.
- The maintainer may squash, edit or close a pull request. Closing one is not a judgment on you.

## License

Visor is under the [GNU Affero General Public License v3.0](LICENSE). By sending a contribution you agree that it is published under the same license.

## Conduct

Everyone who takes part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
