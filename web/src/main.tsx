import "@fontsource-variable/inter";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { store } from "./lib/store";
import "./theme/tokens.css";
import "./styles/tailwind.css";
import { initTheme } from "./theme/theme";

initTheme();
void store.init();
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
