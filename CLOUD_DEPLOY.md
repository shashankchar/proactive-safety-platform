# Cloud Backend Setup

The laptop LAN backend is only for local testing. Real users on Jio, Airtel, Wi-Fi, or any other network need one public backend URL.

Deploy this project root to any Node.js hosting service, for example:

- Render
- Railway
- Fly.io
- AWS
- Google Cloud
- DigitalOcean

The server already listens on `0.0.0.0` and uses `PORT`, so it is cloud-ready.

## Required Command

```text
npm start
```

## Health Check

```text
/api/health
```

## After Deploying

You will receive a public URL like:

```text
https://proactive-safety-backend.onrender.com
```

Use that URL inside every Android app:

```text
Backend URL: https://proactive-safety-backend.onrender.com
```

Then app-to-app cooperative safety works across mobile networks. Users do not need to be on the same Wi-Fi.

## Real Map

The APK now uses OpenStreetMap tiles through a WebView. The map is based on the phone's real GPS coordinates. It is no longer a fake drawn junction.
