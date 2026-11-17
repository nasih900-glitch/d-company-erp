import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, HashRouter } from 'react-router-dom';
import RootRecoveryGate from '@/app/RootRecoveryGate';
import './styles/index.css';

// The ordinary auth subtree must not even evaluate on a cold recovery route.
const Router = import.meta.env.VITE_ROUTER_MODE === 'hash' ? HashRouter : BrowserRouter;
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><Router><RootRecoveryGate/></Router></React.StrictMode>,
);
