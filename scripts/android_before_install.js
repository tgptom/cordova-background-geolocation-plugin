#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

module.exports = function(context) {
    const platformRoot = path.join(context.opts.projectRoot, 'platforms', 'android');

    if (!fs.existsSync(platformRoot)) {
        return;
    }

    const stringsDir = path.join(platformRoot, 'app', 'src', 'main', 'res', 'values');
    const stringsFile = path.join(stringsDir, 'strings.xml');

    if (!fs.existsSync(stringsFile)) {
        if (!fs.existsSync(stringsDir)) {
            fs.mkdirSync(stringsDir, { recursive: true });
        }

        fs.writeFileSync(stringsFile, [
            '<?xml version="1.0" encoding="utf-8"?>',
            '<resources>',
            '</resources>',
            ''
        ].join('\n'), 'utf8');

        console.log('[cordova-background-geolocation-plugin] Created missing strings.xml at:', stringsFile);
    }
};
