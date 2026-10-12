import axios from "axios";

const DEFAULT_API_BASE_URL = "http://localhost:8080";

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL,
  headers: {
    Accept: "application/json",
  },
  timeout: 10_000,
});
