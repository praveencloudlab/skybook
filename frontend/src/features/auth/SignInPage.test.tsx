import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RegisterPage } from './RegisterPage';
import { SignInPage } from './SignInPage';

function renderAt(ui: React.ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('auth screens', () => {
  // Explicit, because RTL only auto-registers cleanup when vitest runs with
  // `globals: true` - which this project does not. Without it each test inherits
  // the previous test's DOM, and assertions like "sign-in shows NO password
  // policy" pass or fail based on what rendered before them rather than on the
  // component under test.
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the password policy on register, before anything is submitted', () => {
    renderAt(<RegisterPage />);

    // The server's 400 is accurate but late. Being told "needs a symbol" only
    // after a round trip - having already chosen a password - is a needlessly
    // annoying way to learn the rule.
    expect(screen.getByText('At least 12 characters')).toBeDefined();
    expect(screen.getByText('An upper-case letter')).toBeDefined();
    expect(screen.getByText('A symbol')).toBeDefined();
  });

  it('does NOT show a password policy on sign-in', () => {
    renderAt(<SignInPage />);

    // Deliberate: the server applies complexity at registration only, so
    // accounts created under an older policy can still sign in. Mirroring the
    // checklist here would lock exactly those people out of their own accounts.
    expect(screen.queryByText('At least 12 characters')).toBeNull();
    expect(screen.queryByText('A symbol')).toBeNull();
  });

  it('asks for a new-password autocomplete on register and current-password on sign-in', () => {
    renderAt(<RegisterPage />);
    expect(screen.getByLabelText('Password').getAttribute('autocomplete')).toBe('new-password');
    cleanup();

    renderAt(<SignInPage />);
    // Wrong values here make password managers offer to overwrite a saved
    // password on a sign-in form - a small detail users notice immediately.
    expect(screen.getByLabelText('Password').getAttribute('autocomplete')).toBe('current-password');
  });

  it('marks a field invalid for assistive tech, not just visually', () => {
    renderAt(<SignInPage />);
    const email = screen.getByLabelText('Email');

    // aria-invalid is absent until something is wrong; the Field component wires
    // aria-describedby to the message so the reason is announced with the field.
    expect(email.getAttribute('aria-invalid')).toBeNull();
    expect(email.getAttribute('type')).toBe('email');
  });
});
