const { google } = require('googleapis');
const { GoogleAuth } = require('google-auth-library');
const fs = require('fs');

async function main() {
    const packageName = 'com.attius.homefrontalert';
    const aabPath = 'C:\\Users\\user\\AppData\\Local\\Temp\\homefrontalert\\app\\build\\outputs\\bundle\\proRelease\\app-pro-release.aab';
    const trackName = 'internal';

    console.log(`--- Starting Play Console Upload for ${packageName} ---`);

    try {
        const auth = new GoogleAuth({
            scopes: ['https://www.googleapis.com/auth/androidpublisher']
        });
        const authClient = await auth.getClient();
        google.options({ auth: authClient });

        const publisher = google.androidpublisher('v3');

        // 1. Create a new edit
        console.log('Creating new edit...');
        const edit = await publisher.edits.insert({ packageName });
        const editId = edit.data.id;
        console.log(`Edit ID: ${editId}`);

        // 2. Upload the AAB
        console.log(`Reading AAB from ${aabPath}...`);
        const aabStats = fs.statSync(aabPath);
        console.log(`AAB size: ${aabStats.size} bytes`);

        const upload = await publisher.edits.bundles.upload({
            editId,
            packageName,
            media: {
                mimeType: 'application/octet-stream',
                body: fs.createReadStream(aabPath)
            }
        });
        const versionCode = upload.data.versionCode;
        console.log(`Upload successful! Version Code: ${versionCode}`);

        // 3. Assign to Track
        console.log(`Assigning version ${versionCode} to track: ${trackName}...`);
        await publisher.edits.tracks.update({
            editId,
            packageName,
            track: trackName,
            requestBody: {
                releases: [
                    {
                        versionCodes: [versionCode.toString()],
                        status: 'draft',
                        releaseNotes: [
                          {
                            language: 'en-US',
                            text: '1.7.5 Performance and Policy Update. Fully compliant with new Play Store location policies. Reduced siren latency.'
                          },
                          {
                            language: 'iw-IL',
                            text: 'גרסת 1.7.5 - עדכון פרטיות ושיפור ביצועים. האפליקציה בגרסה זו אינה דורשת מיקום ברקע (רק בזמן שימוש) בהתאם למדיניות גוגל. סירנה מהירה יותר.'
                          }
                        ]
                    }
                ]
            }
        });

        // 4. Commit
        console.log('Commiting changes...');
        await publisher.edits.commit({ editId, packageName });
        console.log('--- SUCCESS! Version 1.7.5 (Build 28) is now in Draft on Internal Track. ---');

    } catch (e) {
        console.error('--- Play API ERROR ---');
        console.error(e.message);
        if (e.response && e.response.data) {
            console.error(JSON.stringify(e.response.data, null, 2));
        }
    }
}

main();
