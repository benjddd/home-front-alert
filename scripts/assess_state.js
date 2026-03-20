const { google } = require('googleapis');
const { GoogleAuth } = require('google-auth-library');

async function assessState() {
  const auth = new GoogleAuth({
    scopes: ['https://www.googleapis.com/auth/androidpublisher']
  });
  const publisher = google.androidpublisher({ version: 'v3', auth });
  const packageName = 'com.attius.homefrontalert';

  try {
    console.log('--- STARTING GOOGLE PLAY CONSOLE AUDIT ---');

    // 1. Get Edits
    const edit = await publisher.edits.insert({ packageName });
    const editId = edit.data.id;

    // 2. Check Tracks
    console.log('\n--- TRACK STATUS ---');
    const tracks = await publisher.edits.tracks.list({ packageName, editId });
    tracks.data.tracks.forEach(track => {
      console.log(`Track: ${track.track}`);
      track.releases.forEach(release => {
        console.log(`  Version: ${release.name} (Builds: ${release.versionCodes.join(', ')}) - Status: ${release.status}`);
      });
    });

    // 3. Check App Content (Declarations) - Basic check for version codes
    console.log('\n--- BUNDLE AUDIT ---');
    const bundles = await publisher.edits.bundles.list({ packageName, editId });
    bundles.data.bundles.forEach(bundle => {
      console.log(`Bundle in Library: Build ${bundle.versionCode} (Last modified timing available)`);
    });

    console.log('\n--- AUDIT COMPLETE ---');
  } catch (err) {
    console.error('Audit Error:', err.message);
  }
}

assessState();
