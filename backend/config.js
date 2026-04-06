'use strict';

/**
 * config.js — Backend relay configuration.
 * Single source of truth for all tuneable backend constants.
 */

module.exports = {

    // ── HFC Poller ──────────────────────────────────────────────────────────
    POLL_INTERVAL_MS: 1000,
    HFC_REQUEST_TIMEOUT_MS: 3000,
    KEEPALIVE_INTERVAL_MS: 5 * 60 * 1000, // 5 minutes

    // ── Alert type normalization ────────────────────────────────────────────
    ALERT_TYPE_MAP: {
        // Rocket / missile
        'ירי רקטות וטילים': 'ROCKET',
        'ירי רקטות':        'ROCKET',
        'rocket':            'ROCKET',
        'missiles':          'ROCKET',
        'היכנסו למרחב מוגן עכשיו!': 'ROCKET',
        // UAV / drone
        'חדירת כלי טיס עוין': 'UAV',
        'כלי טיס עוין':        'UAV',
        'uav':                  'UAV',
        'drone':                'UAV',
        // Infiltration
        'חדירת מחבלים': 'INFILTRATION',
        'infiltration':  'INFILTRATION',
        // Pre-warning
        'התרעה מוקדמת': 'PRE_WARNING',
        'בדקות הקרובות צפויות להתקבל התרעות באזורך': 'PRE_WARNING',
        'הכינו עצמכם': 'PRE_WARNING',
        'pre-warning':  'PRE_WARNING',
        'pre_warning':  'PRE_WARNING',
        // All-clear (documented HFC phrases)
        'האירוע הסתיים':                             'CALM',
        'חזרה לשגרה':                                'CALM',
        'השוהים במרחב המוגן יכולים לצאת':            'CALM',
        'calm':                                       'CALM',
        'clear':                                      'CALM',
        'all_clear':                                  'CALM',
        // Other
        'רעידת אדמה':           'OTHER',
        'צונאמי':               'OTHER',
        'אירוע רדיולוגי':       'OTHER',
        'אירוע קרינה':          'OTHER',
        'אירוע חומרים מסוכנים': 'OTHER',
        'חשש לצונאמי':          'OTHER',
        'התרעה ביטחונית':       'OTHER',
    },
};
