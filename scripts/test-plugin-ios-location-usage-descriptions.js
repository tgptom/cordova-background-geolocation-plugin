const fs = require('fs');
const path = require('path');

const pluginXmlPath = path.join(__dirname, '..', 'plugin.xml');
const pluginXml = fs.readFileSync(pluginXmlPath, 'utf8');

function expectMatch(regex, message) {
  if (!regex.test(pluginXml)) {
    throw new Error(message);
  }
}

expectMatch(/<preference\s+name="ALWAYS_USAGE_DESCRIPTION"\s+default="[^"]+"\s*\/>/, 'Missing ALWAYS_USAGE_DESCRIPTION preference');
expectMatch(/<preference\s+name="WHEN_IN_USE_USAGE_DESCRIPTION"\s+default="This app requires location access while in use"\s*\/>/, 'Missing WHEN_IN_USE_USAGE_DESCRIPTION preference with expected default');
expectMatch(/<config-file\s+target="\*-Info\.plist"\s+parent="NSLocationAlwaysUsageDescription">\s*<string>\$ALWAYS_USAGE_DESCRIPTION<\/string>\s*<\/config-file>/, 'NSLocationAlwaysUsageDescription must map to ALWAYS_USAGE_DESCRIPTION');
expectMatch(/<config-file\s+target="\*-Info\.plist"\s+parent="NSLocationWhenInUseUsageDescription">\s*<string>\$WHEN_IN_USE_USAGE_DESCRIPTION<\/string>\s*<\/config-file>/, 'NSLocationWhenInUseUsageDescription must map to WHEN_IN_USE_USAGE_DESCRIPTION');
expectMatch(/<config-file\s+target="\*-Info\.plist"\s+parent="NSLocationAlwaysAndWhenInUseUsageDescription">\s*<string>\$ALWAYS_USAGE_DESCRIPTION<\/string>\s*<\/config-file>/, 'NSLocationAlwaysAndWhenInUseUsageDescription must map to ALWAYS_USAGE_DESCRIPTION');

console.log('plugin.xml iOS location usage-description mappings are valid.');
