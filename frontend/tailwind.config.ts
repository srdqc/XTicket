import type { Config } from 'tailwindcss'

const config: Config = {
  content: ['./src/**/*.{js,ts,jsx,tsx,mdx}'],
  theme: {
    extend: {
      colors: {
        primary: '#5b5bd6',
        secondary: '#0e7490',
        gold: '#d97706',
      },
    },
  },
  plugins: [],
}

export default config
