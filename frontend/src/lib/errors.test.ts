import { describe, expect, it } from 'vitest';
import { ApiError, fieldErrorCount, fieldErrors, userMessage, violationMessages } from './errors';

/**
 * Parsing the server's joined violation string (FRONTEND_MODULE.md §4).
 *
 * The counting rule carries the weight here, because getting it wrong is
 * invisible in code review and loud in production: the modify-booking dialog
 * told a user "please check the 2 highlighted fields" when exactly one field
 * was wrong - the map holds every nested violation twice (full path plus a
 * short alias) so forms can look it up either way, and the count was counting
 * lookup keys instead of problems.
 */
function validation(message: string): ApiError {
  return new ApiError('validation', 400, 'ignored', {
    timestamp: '2026-08-06T00:00:00Z',
    status: 400,
    error: 'Bad Request',
    message,
    path: '/api/bookings',
  });
}

describe('field violations', () => {
  it('counts problems, not the aliases that point at them', () => {
    const error = validation('contact.contactPhone: Contact phone is required');

    expect(fieldErrorCount(error)).toBe(1);
    // Both lookups still work - that is what the alias is for.
    expect(fieldErrors(error)['contact.contactPhone']).toBe('Contact phone is required');
    expect(fieldErrors(error).contactPhone).toBe('Contact phone is required');
  });

  it('counts each distinct field once when several are wrong', () => {
    const error = validation(
      'contact.contactPhone: Contact phone is required, contact.contactEmail: Contact email is required',
    );

    expect(fieldErrorCount(error)).toBe(2);
    expect(userMessage(error)).toBe('Please check the 2 highlighted fields.');
  });

  it('keeps a message containing a comma intact', () => {
    const error = validation('dob: Date of birth must be in the past, not today');

    expect(fieldErrorCount(error)).toBe(1);
    expect(fieldErrors(error).dob).toBe('Date of birth must be in the past, not today');
  });

  it('says "the highlighted field" for a single problem', () => {
    expect(userMessage(validation('contact.contactPhone: Contact phone is required')))
      .toBe('Please check the highlighted field.');
  });

  it('exposes the server messages for a form that cannot highlight anything', () => {
    // A dialog rebuilding a payload from an existing booking has no input to
    // point at, so it must be able to SAY what is wrong instead.
    const error = validation('contact.contactPhone: Contact phone is required');

    expect(violationMessages(error)).toEqual(['Contact phone is required']);
  });

  it('falls back to the server message when nothing parses as a field', () => {
    const error = validation('Booking is not modifiable');

    expect(fieldErrorCount(error)).toBe(0);
    expect(violationMessages(error)).toEqual([]);
    expect(userMessage(error)).toBe('Booking is not modifiable');
  });
});
