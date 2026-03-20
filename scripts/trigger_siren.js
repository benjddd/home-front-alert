const admin = require('firebase-admin');
const path = require('path');

/**
 * 🚨 TRIGGER SIREN SCRIPT 🚨
 * Use this to trigger a real siren on your device for Google Play video recording.
 * 
 * Instructions:
 * 1. Ensure your phone has the latest APK installed.
 * 2. Run: node scripts/trigger_siren.js
 * 3. Record your phone (using another device) from the Home Screen.
 */

// Point to the service account key
const serviceAccount = require(path.join(__dirname, '../backend/serviceAccountKey.json'));

if (!admin.apps.length) {
    admin.initializeApp({
        credential: admin.credential.cert(serviceAccount)
    });
}

const message = {
    data: {
        alertId: "PLAY_TEST_" + Date.now(),
        type: "Rocket Alert",
        // Test cities: Major hubs to ensure one is 'Local' or 'Caution'
        cities: JSON.stringify(["ירושלים - מערב", "תל אביב - מרכז", "חיפה - מערב"]), 
        chunkInfo: "1/1"
    },
    topic: 'alerts'
};

console.log("📡 Sending test siren to 'alerts' topic...");

admin.messaging().send(message)
    .then((response) => {
        console.log("✅ SUCCESS! Message sent:", response);
        console.log("\n🎬 RECORDING PLAN:");
        console.log("1. Start video with the phone on the HOME SCREEN (app in background).");
        console.log("2. Wait for the notification/siren to trigger.");
        console.log("3. Show the notification, then tap it to open the app.");
        console.log("4. This video link is required for the Foreground Service permission.");
        process.exit(0);
    })
    .catch((error) => {
        console.error("❌ Error sending message:", error);
        process.exit(1);
    });
