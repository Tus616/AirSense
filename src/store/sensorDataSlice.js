import { createSlice, createAsyncThunk } from "@reduxjs/toolkit";
import {
  getCitySummary,
  getNeighborhoods,
  getForecast,
  getAdvisory,
  getStations,
  getTrends,
  getReliability,
  getSourceAttribution,
  getHeatmap,
  getPolluters,
  getPlume,
  getGovAdvisories,
  getGridForecast
} from "../services/api";

export const fetchPublicData = createAsyncThunk(
  "sensorData/fetchPublicData",
  async (cityId, { rejectWithValue }) => {
    try {
      const [citySummary, neighborhoods, forecast] = await Promise.all([
        getCitySummary(cityId),
        getNeighborhoods(cityId),
        getForecast()
      ]);
      const healthAdvisory = neighborhoods.length > 0 ? await getAdvisory(neighborhoods[0].id) : await getAdvisory();
      return { citySummary, neighborhoods, forecast, healthAdvisory };
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Failed to fetch public data");
    }
  }
);

export const fetchGovData = createAsyncThunk(
  "sensorData/fetchGovData",
  async (cityId, { rejectWithValue }) => {
    try {
      const [stations, trends, reliability, sourceAttribution, heatmapPoints, industrialPolluters, predictedPlume, govAdvisories, gridForecast] = await Promise.all([
        getStations(cityId),
        getTrends(cityId),
        getReliability(cityId),
        getSourceAttribution(cityId),
        getHeatmap(cityId),
        getPolluters(cityId),
        getPlume(cityId),
        getGovAdvisories(cityId),
        getGridForecast(cityId)
      ]);
      return {
        stations,
        trends,
        reliability,
        sourceAttribution,
        heatmapPoints,
        industrialPolluters,
        predictedPlume,
        govAdvisories,
        gridForecast
      };
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Failed to fetch government data");
    }
  }
);

const initialState = {
  citySummary: null,
  stations: [],
  neighborhoods: [],
  trends: [],
  reliability: [],
  sourceAttribution: [],
  industrialPolluters: [],
  heatmapPoints: [],
  predictedPlume: null,
  govAdvisories: [],
  gridForecast: [],
  forecast: [],
  healthAdvisory: null,
  status: "idle", // 'idle' | 'loading' | 'succeeded' | 'failed'
  error: null
};

const sensorDataSlice = createSlice({
  name: "sensorData",
  initialState,
  reducers: {
    clearSensorData(state) {
      Object.assign(state, initialState);
    }
  },
  extraReducers: (builder) => {
    builder
      // Public Data
      .addCase(fetchPublicData.pending, (state) => {
        state.status = "loading";
      })
      .addCase(fetchPublicData.fulfilled, (state, action) => {
        state.status = "succeeded";
        state.citySummary = action.payload.citySummary;
        state.neighborhoods = action.payload.neighborhoods;
        state.forecast = action.payload.forecast;
        state.healthAdvisory = action.payload.healthAdvisory;
      })
      .addCase(fetchPublicData.rejected, (state, action) => {
        state.status = "failed";
        state.error = action.payload;
      })
      // Gov Data
      .addCase(fetchGovData.pending, (state) => {
        state.status = "loading";
      })
      .addCase(fetchGovData.fulfilled, (state, action) => {
        state.status = "succeeded";
        state.stations = action.payload.stations;
        state.trends = action.payload.trends;
        state.reliability = action.payload.reliability;
        state.sourceAttribution = action.payload.sourceAttribution;
        state.heatmapPoints = action.payload.heatmapPoints;
        state.industrialPolluters = action.payload.industrialPolluters;
        state.predictedPlume = action.payload.predictedPlume;
        state.govAdvisories = action.payload.govAdvisories;
        state.gridForecast = action.payload.gridForecast;
      })
      .addCase(fetchGovData.rejected, (state, action) => {
        state.status = "failed";
        state.error = action.payload;
      });
  }
});

export const { clearSensorData } = sensorDataSlice.actions;
export default sensorDataSlice.reducer;
