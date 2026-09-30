/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_HOMEOPS_DEMO?: 'true' | 'false'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
