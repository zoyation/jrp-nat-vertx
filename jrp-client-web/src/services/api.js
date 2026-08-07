import axios from 'axios';
// Create axios instance with base configuration

function resolveApiBase() {
    const path = window.location.pathname;        // 例如 /jrp-client/web/ 或 /app/jrp-client/web/
    // 去掉末尾的 /web（及可选斜杠），得到接口前缀
    const base = path.replace(/\/web\/?$/, '');
    return base || '/jrp-client';
}
const apiClient = axios.create({
  baseURL: resolveApiBase(),
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
apiClient.interceptors.request.use(
  (config) => {
    // Add auth token if available
    const token = localStorage.getItem('authToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor
apiClient.interceptors.response.use(
  (response) => response.data,
  (error) => {
    // Handle common errors
    if (error.response) {
      switch (error.response.status) {
        case 401:
          // Handle unauthorized
          break;
        case 404:
          // Handle not found
          break;
        default:
          // Handle other errors
          break;
      }
    }
    return Promise.reject(error);
  }
);


// API methods

export default {
  // Config related
    getConfig() {
        return apiClient.get('/config/listRemoteProxies');
    },
    saveConfig(data) {
        return apiClient.post('/config/saveRemoteProxies', data);
    },
    status() {
      return apiClient.get('/config/statusRemoteProxies');
    },
    getUserConfig() {
        return apiClient.get('/config/listUserProxies');
    },
    saveUserConfig(data) {
        return apiClient.post('/config/saveUserProxies', data);
    },
    statusUser() {
      return apiClient.get('/config/statusUserProxies');
    }
};