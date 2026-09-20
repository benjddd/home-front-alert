const { google } = require('googleapis');
const { GoogleAuth } = require('google-auth-library');

(async () => {
  const auth = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher'] });
  const client = await auth.getClient();
  google.options({ auth: client });
  const pkg = 'com.attius.homefrontalert';
  const ap = google.androidpublisher('v3');
  const edit = await ap.edits.insert({ packageName: pkg });
  console.log('editId', edit.data.id);
  const tracks = await ap.edits.tracks.list({ packageName: pkg, editId: edit.data.id });
  console.log('tracks', (tracks.data.tracks || []).map(t => t.track).join(', '));
  const listings = await ap.edits.listings.list({ packageName: pkg, editId: edit.data.id });
  console.log('listings', (listings.data.listings || []).map(l => l.language).join(', '));
  await ap.edits.delete({ packageName: pkg, editId: edit.data.id });
  console.log('OK');
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
