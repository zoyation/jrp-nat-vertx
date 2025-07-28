import axios from 'axios';
// Create axios instance with base configuration
const apiClient = axios.create({
  baseURL: '/jrp-client',
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
        return apiClient.get('/config/list');
    },
    saveConfig(data) {
        return apiClient.post('/config/save', data);
    },
    status() {
      return apiClient.get('/config/status');
    },
    // Example for other endpoints
    getResource(id) {
        return apiClient.get(`/resources/${id}`);
    },

    createResource(data) {
        return apiClient.post('/resources', data);
    },

    updateResource(id, data) {
        return apiClient.put(`/resources/${id}`, data);
    },

    deleteResource(id) {
        return apiClient.delete(`/resources/${id}`);
    }
};