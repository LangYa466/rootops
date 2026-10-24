# RootOps QA tour report

- Generated: 2026-10-25 00:29:41
- Started: 2026-10-25 00:26:02
- Mode: device run
- Serial: (default adb device)
- APK: app/build/outputs/apk/debug/app-debug.apk
- Activity: dev.rootops/.MainActivity

## Result

**PASS**

- Required screens (Home, Files, Terminal, Tools): all PASS
- Crash/exception excerpts: 0
- App foreground at the end: yes
- Manual checks: 5/5 PASS

## Screens

| Screen | Result | Note |
| --- | --- | --- |
| Home | PASS | tapped at (126, 2208) |
| Files | PASS | tapped at (211, 1567) |
| Terminal | PASS | tapped at (677, 2208) |
| Tools | PASS | tapped at (953, 1441) |
| Settings | PASS | tapped at (133, 1861) after 1 scroll(s) |
| Processes | PASS | tapped at (149, 493) |
| Logcat | PASS | tapped at (642, 493) |
| Network | PASS | tapped at (133, 829) |
| Scripts | PASS | tapped at (643, 829) |
| Schedule | PASS | tapped at (140, 1165) |
| Mounts | PASS | tapped at (648, 1165) |
| Packages | PASS | tapped at (144, 1501) |
| SSH | PASS | tapped at (621, 1501) |
| SFTP | PASS | tapped at (109, 1837) |
| Analyzer | PASS | tapped at (656, 1837) |

## Steps

| # | Action | Target | Result | Screenshot | Note |
| --- | --- | --- | --- | --- | --- |
| 1 | start | dev.rootops/.MainActivity | OK | qa/01-launch.png | am start -W + 3s settle |
| 2 | tap | Home | PASS | qa/02-home.png | tapped at (126, 2208) |
| 3 | tap | Files | PASS | qa/03-files.png | tapped at (211, 1567) |
| 4 | tap | Terminal | PASS | qa/04-terminal.png | tapped at (677, 2208) |
| 5 | tap | Tools | PASS | qa/05-tools.png | tapped at (953, 1441) |
| 6 | tap | Settings | PASS | qa/06-settings.png | tapped at (133, 1861) after 1 scroll(s) |
| 7 | tap | Processes | PASS | qa/07-processes.png | tapped at (149, 493) |
| 8 | tap | Logcat | PASS | qa/08-logcat.png | tapped at (642, 493) |
| 9 | tap | Network | PASS | qa/09-network.png | tapped at (133, 829) |
| 10 | tap | Scripts | PASS | qa/10-scripts.png | tapped at (643, 829) |
| 11 | tap | Schedule | PASS | qa/11-schedule.png | tapped at (140, 1165) |
| 12 | tap | Mounts | PASS | qa/12-mounts.png | tapped at (648, 1165) |
| 13 | tap | Packages | PASS | qa/13-packages.png | tapped at (144, 1501) |
| 14 | tap | SSH | PASS | qa/14-ssh.png | tapped at (621, 1501) |
| 15 | tap | SFTP | PASS | qa/15-sftp.png | tapped at (109, 1837) |
| 16 | tap | Analyzer | PASS | qa/16-analyzer.png | tapped at (656, 1837) |

## Manual checks

| Check | Result | Evidence | Screenshot |
| --- | --- | --- | --- |
| Root access re-check | PASS | root access AVAILABLE (tapped at (133, 1861) after 1 scroll(s)) | qa/17-root-status.png |
| Home root chip | PASS | Home root chip shows root (tapped at (126, 2208)) | qa/18-home-root.png |
| Files /data listing | PASS | path field='/data'; /data entries seen: data, adb, apex, app, data | qa/19-files-data.png |
| Root-only file read | PASS | path field='/data/local/tmp'; editor content='root-only secret' | qa/20-root-file.png |
| Terminal root + pty | PASS | typed=[' su -c id', ' tty', ' printenv TERM']; uid=0 seen; pty seen; root session switched to sh (tapped at (538, 1567)) | qa/21-terminal-pty.png |

## Terminal output sample

```text
:/storage/emulated/0 $  su -c id
uid=0(root) gid=0(root) groups=0(root) context=u:r
:/storage/emulated/0 $  tty
/dev/pts/1
:/storage/emulated/0 $  printenv TERM
xterm-256color
```

## Failed taps

- (none)

## Crash / exception excerpts

(none)

## Final foreground state

```text
topResumedActivity=ActivityRecord{ff2b177 u0 dev.rootops/.MainActivity t83}
```

