'use strict';
// The one bridge between the app's windows and the main process: send, invoke and listen on
// named channels. Windows get nothing else from Node.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('dd', {
  send: (channel, ...args) => ipcRenderer.send(channel, ...args),
  invoke: (channel, ...args) => ipcRenderer.invoke(channel, ...args),
  on: (channel, listener) => {
    const wrapped = (_event, ...args) => listener(...args);
    ipcRenderer.on(channel, wrapped);
    return () => ipcRenderer.removeListener(channel, wrapped);
  },
});
