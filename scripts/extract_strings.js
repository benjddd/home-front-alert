const fs = require('fs');
const path = require('path');
const cfg = {
  src: [{l:'en',p:'android-app/app/src/main/res/values/strings.xml'},{l:'he',p:'android-app/app/src/main/res/values-iw/strings.xml'}],
  out: 'backend/public/status_strings.json',
  keys: {critical:'critical_status',warning:'warning_status',threat:'threat_status',no_alerts:'no_alerts',all_clear:'notif_ok'}
};
const res = {};
cfg.src.forEach(s => {
  const c = fs.readFileSync(s.p, 'utf8');
  res[s.l] = {};
  Object.entries(cfg.keys).forEach(([k, a]) => {
    const m = c.match(new RegExp(`<string name="${a}">(.*?)</string>`));
    if (m) res[s.l][k] = m[1].replace(/<[^>]*>/g, '').trim();
  });
});
fs.writeFileSync(cfg.out, JSON.stringify(res, null, 2));
console.log('Done');
