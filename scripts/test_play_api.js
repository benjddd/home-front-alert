const { google } = require('googleapis');
const { GoogleAuth } = require('google-auth-library');

async function main() {
    console.log('--- Initializing Google Play Publisher (Internal Tool) ---');
    try {
        const auth = new GoogleAuth({
            scopes: ['https://www.googleapis.com/auth/androidpublisher']
        });
        const authClient = await auth.getClient();
        google.options({ auth: authClient });

        const publisher = google.androidpublisher('v3');
        const packageName = 'com.attius.homefrontalert';

        console.log(`Connecting to Play API for: ${packageName}...`);
        
        // 1. Fetch current tracks
        const edits = await publisher.edits.insert({ packageName });
        const editId = edits.data.id;
        console.log(`Created new transaction: ${editId}`);

        const tracks = await publisher.edits.tracks.list({ packageName, editId });
        console.log('Active Tracks Detected:');
        tracks.data.tracks.forEach(track => {
            console.log(` - ${track.track}: ${track.releases?.length || 0} releases`);
        });

        // 2. Clean up (don't commit yet)
        await publisher.edits.delete({ packageName, editId });
        console.log('--- Verification Success. Ready for upload. ---');
    } catch (e) {
        console.error('--- Play API ERROR ---');
        console.error(e.message);
        if (e.response) console.error(JSON.stringify(e.response.data, null, 2));
    }
}

main();
