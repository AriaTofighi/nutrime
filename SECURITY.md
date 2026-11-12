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
- Registration always requires a single-use invitation bound to the invited email. Share codes privately.
- Set `ALLOW_REGISTRATION=false` to pause all new accounts, including valid invitations.
- Set `ADMIN_EMAILS` only to trusted owner accounts. These accounts can create and revoke invitations
  and access the food database and assistant traces. Keep it empty to disable these admin APIs.
- Deploy invitation support on the server before distributing the first public APK.
- The server URL in an APK is public information. Access control depends on accounts and invitations.
