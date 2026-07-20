import { configureStore } from "@reduxjs/toolkit";
import authReducer from "./authSlice";
import sensorDataReducer from "./sensorDataSlice";
import uiStateReducer from "./uiStateSlice";

const store = configureStore({
  reducer: {
    auth: authReducer,
    sensorData: sensorDataReducer,
    uiState: uiStateReducer,
  },
});

export default store;
