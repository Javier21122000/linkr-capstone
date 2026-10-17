import axios from "axios";

const DEFAULT_API_BASE_URL = "";

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL,
  headers: {
    Accept: "application/json",
  },
  timeout: 10_000,
});
