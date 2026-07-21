import { createSlice, createAsyncThunk } from "@reduxjs/toolkit";
import { loginApi, registerApi, logoutApi, getMeApi } from "../services/api";

export const loginAsync = createAsyncThunk(
  "auth/loginAsync",
  async ({ email, password }, { rejectWithValue }) => {
    try {
      const response = await loginApi(email, password);
      return response; // { token, user, role, expiresIn }
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Login failed");
    }
  }
);
export const registerAsync = createAsyncThunk(
  "auth/registerAsync",
  async ({ name, email, password }, { rejectWithValue }) => {
    try {
      const response = await registerApi(name, email, password);
      return response;
    } catch (err) {
      return rejectWithValue(
        err.response?.data?.message || "Registration failed"
      );
    }
  }
);

export const logoutAsync = createAsyncThunk(
  "auth/logoutAsync",
  async (_, { rejectWithValue }) => {
    try {
      await logoutApi();
      return true;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Logout failed");
    }
  }
);

const initialState = {
  user: null,
  token: null,
  role: null,
  isAuthenticated: false,
  status: "idle", // 'idle' | 'loading' | 'succeeded' | 'failed'
  error: null,
};

const authSlice = createSlice({
  name: "auth",
  initialState,
  reducers: {
    // Keep synchronous for forced resets if needed
    logout(state) {
      state.user = null;
      state.token = null;
      state.role = null;
      state.isAuthenticated = false;
      state.status = "idle";
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(loginAsync.pending, (state) => {
        state.status = "loading";
        state.error = null;
      })
      .addCase(loginAsync.fulfilled, (state, action) => {
        state.status = "succeeded";
        state.user = action.payload.user;
        state.token = action.payload.token;
        state.role = action.payload.role;
        state.isAuthenticated = true;
      })
      .addCase(loginAsync.rejected, (state, action) => {
        state.status = "failed";
        state.error = action.payload;
      })
      .addCase(registerAsync.pending, (state) => {
  state.status = "loading";
  state.error = null;
})
.addCase(registerAsync.fulfilled, (state, action) => {
  state.status = "succeeded";
  state.user = action.payload.user;
  state.token = action.payload.token;
  state.role = action.payload.role;
  state.isAuthenticated = true;
})
.addCase(registerAsync.rejected, (state, action) => {
  state.status = "failed";
  state.error = action.payload;
})
      .addCase(logoutAsync.fulfilled, (state) => {
        state.user = null;
        state.token = null;
        state.role = null;
        state.isAuthenticated = false;
        state.status = "idle";
      });
  },
});

export const { logout } = authSlice.actions;
export default authSlice.reducer;

