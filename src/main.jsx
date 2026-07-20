import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Provider } from "react-redux";
import store from "./store";
import "@fontsource/space-mono/400.css";
import "@fontsource/space-mono/700.css";
import "@fontsource/manrope/400.css";
import "@fontsource/manrope/500.css";
import "@fontsource/manrope/700.css";
import "./index.css";
import "./styles/tokens.css";
import "./styles/typography.css";
import "./styles/animations.css";
import "./styles/layout.css";
import App from "./App";

const storedTheme = window.localStorage.getItem("airsense-theme");
const preferredTheme = storedTheme === "dark" || storedTheme === "light"
  ? storedTheme
  : window.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light";
document.documentElement.dataset.theme = preferredTheme;

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <Provider store={store}>
      <App />
    </Provider>
  </StrictMode>
);
