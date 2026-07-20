import { createSlice } from "@reduxjs/toolkit";

const initialState = {
  selectedCity: "",
  selectedCityMetadata: null,
  govTab: "map",           // "map" | "analytics" | "advisories" | "simulations" | "health" | "enforcement" | "compare"
  citizenTab: "aqi",       // "aqi" | "prediction" | "settings"
  isVulnerable: false,     // health vulnerability toggle
  alertsOptIn: false,      // personalized alerts opt-in
  loading: false,
  error: null,
};

const uiStateSlice = createSlice({
  name: "uiState",
  initialState,
  reducers: {
    setSelectedCity(state, action) {
      if (typeof action.payload === "object" && action.payload !== null) {
        state.selectedCity = action.payload.cityId || action.payload.id || "";
        state.selectedCityMetadata = { ...(state.selectedCityMetadata || {}), ...action.payload };
      } else {
        state.selectedCity = action.payload || "";
        state.selectedCityMetadata = action.payload
          ? {
              ...(state.selectedCityMetadata || {}),
              cityId: action.payload,
              cityName: action.payload,
              displayName: action.payload,
            }
          : null;
      }
    },
    setSelectedCityMetadata(state, action) {
      state.selectedCityMetadata = { ...(state.selectedCityMetadata || {}), ...action.payload };
      state.selectedCity = state.selectedCityMetadata.cityId || "";
    },
    setGovTab(state, action) {
      state.govTab = action.payload;
    },
    setCitizenTab(state, action) {
      state.citizenTab = action.payload;
    },
    toggleVulnerable(state) {
      state.isVulnerable = !state.isVulnerable;
    },
    toggleAlerts(state) {
      state.alertsOptIn = !state.alertsOptIn;
    },
    setLoading(state, action) {
      state.loading = action.payload;
    },
    setError(state, action) {
      state.error = action.payload;
    },
  },
});

export const {
  setSelectedCity,
  setSelectedCityMetadata,
  setGovTab,
  setCitizenTab,
  toggleVulnerable,
  toggleAlerts,
  setLoading,
  setError,
} = uiStateSlice.actions;
export default uiStateSlice.reducer;
