# Setup: Claude subscription on the glasses

Goal: "Hey Meta" is answered by your Claude Pro/Max plan through Visor and a small bridge running in Termux. Why it works and what was measured: [findings.md](findings.md).

You need: an Android phone with this fork of Visor installed and your glasses already set up in it, a Claude Pro or Max account, and about 20 minutes. Claude Code does not list Android as a supported platform, so this is experimental, and your plan's usage limits apply.

## 1. Termux

1. Install Termux from F-Droid or GitHub, not the Play Store (that build is obsolete).
2. In Termux:

   ```sh
   pkg update
   pkg install proot-distro
   proot-distro install ubuntu
   proot-distro login ubuntu
   ```

   From here on, "in Ubuntu" means that last prompt (`root@localhost`).

## 2. Node and Claude Code (in Ubuntu)

Claude Code needs a recent Node (20 or newer). Check with `node -v` after the install; if Ubuntu's package is older, install a newer one (for example with [nvm](https://github.com/nvm-sh/nvm)).

```sh
apt update && apt install -y nodejs npm curl
npm install -g @anthropic-ai/claude-code
claude
```

`claude` asks how to log in: choose the Claude subscription. It prints a link; open it in the phone's browser, sign in, and paste the code it gives back into Termux. Then check it works:

```sh
claude -p "Reply with OK"
```

Log in inside Ubuntu, not in plain Termux: the bridge runs there and uses that login.

## 3. The bridge (in Ubuntu)

```sh
cd /root
curl -O https://raw.githubusercontent.com/henriqueleote/visor/main/bridge.js
node bridge.js
```

It prints `Claude voice bridge on http://127.0.0.1:8080`. From a second Termux session, `curl http://127.0.0.1:8080/v1/models` should answer. Stop it with Ctrl+C.

The file must be at `/root/bridge.js` in a distro called `ubuntu`: the app's Start button runs exactly that.

## 4. Visor

1. Settings > Meta AI > Provider > **Claude subscription**. It fills in server `http://127.0.0.1:8080/v1` and model `claude-code`; leave the key blank.
2. Make sure the bridge is running (step 3, or the Start button below) and ask a question through the glasses.

## 5. Start and stop from the app (optional)

Without this, you start the bridge by hand in Termux each time, and it stops when its command ends (a proot kills what it started, so it must run as the foreground command of its own session).

1. In Termux (outside Ubuntu): `mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties`, then `termux-reload-settings`. Close Termux fully and open it again.
2. In Visor, on the Claude subscription tab, tap **Allow** on the Bridge row and grant the Termux permission.
3. Tap **Start**. The row shows "Running in Termux." when the bridge answers.
4. Turn off battery optimisation for Termux, or Android will kill it in the background.

## 6. Glasses settings that matter

- Follow-ups without the wake word: Settings > Meta AI > **Respond without "Hey Meta"**.
- A custom wake word ("Hey Jarvis") needs the US English language pack on the glasses.
- Music by voice: start something in Spotify once so it has a media session, then pick it in Visor's media settings.

## If it does not work

See the troubleshooting table in [findings.md](findings.md). The first checks: the Bridge row says Running, `claude -p "Reply with OK"` works in Ubuntu, and the provider is **Claude subscription**, not **Claude API**.

## Not verified

These steps were written from the working setup, not from a clean install: the Node version, the browser sign-in flow and the `curl` download were not re-tested from scratch. If a step differs on your phone, say so and fix it here.
