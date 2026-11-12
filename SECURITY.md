# Security policy

Please do not report security problems in public issues.

Report them privately through GitHub: open the repository's **Security** tab and choose
**Report a vulnerability**. Include the steps to reproduce the problem and the affected component
(Android app, web app or server).

You will get a reply as soon as possible. Fixes are made on `main`; there are no separate supported
release branches.

## Running your own server

- Set a long random `JWT_SECRET` and `POSTGRES_PASSWORD`. Never commit `.env`.
- Serve the stack over HTTPS.
- After you create your accounts, set `ALLOW_REGISTRATION=false`.
- Keep `ADMIN_EMAILS` empty unless you need the read-only food database panel.
