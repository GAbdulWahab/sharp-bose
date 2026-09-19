export interface ThemeColors {
  bg: string;
  bgSecondary: string;
  card: string;
  cardBorder: string;
  textPrimary: string;
  textSecondary: string;
  textMuted: string;
  accentCyan: string;
  accentEmerald: string;
  accentRose: string;
  accentBlue: string;
  accentPurple: string;
  cardHighlight: string;
  statusBar: 'light-content' | 'dark-content';
}

export const darkTheme: ThemeColors = {
  bg: '#090D16',
  bgSecondary: '#0F172A',
  card: '#1E293B',
  cardBorder: '#334155',
  textPrimary: '#F8FAFC',
  textSecondary: '#94A3B8',
  textMuted: '#64748B',
  accentCyan: '#06B6D4',
  accentEmerald: '#10B981',
  accentRose: '#F43F5E',
  accentBlue: '#38BDF8',
  accentPurple: '#8B5CF6',
  cardHighlight: '#0F283D',
  statusBar: 'light-content',
};

export const lightTheme: ThemeColors = {
  bg: '#F1F5F9',
  bgSecondary: '#E2E8F0',
  card: '#FFFFFF',
  cardBorder: '#CBD5E1',
  textPrimary: '#0F172A',
  textSecondary: '#475569',
  textMuted: '#94A3B8',
  accentCyan: '#0891B2',
  accentEmerald: '#059669',
  accentRose: '#E11D48',
  accentBlue: '#0284C7',
  accentPurple: '#7C3AED',
  cardHighlight: '#E0F2FE',
  statusBar: 'dark-content',
};
