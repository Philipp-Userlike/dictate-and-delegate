'use strict';
// Placeholder while the app is wired; replaced below.
const { app, Tray } = require('electron');
app.whenReady().then(() => { new Tray(require('path').join(__dirname, '..', 'renderer', 'trayTemplate.png')); });
