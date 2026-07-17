import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './index.css';

// Static message/modal helpers render outside the app tree. Give their holder the
// same locale and theme so Ant Design does not fall back to an unthemed context.
ConfigProvider.config({
  holderRender: (children) => (
    <ConfigProvider
      locale={zhCN}
      theme={{ token: { colorPrimary: '#1677ff', borderRadius: 6 } }}
    >
      {children}
    </ConfigProvider>
  ),
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
