# NUMM accessibility implementation status

The frontend is designed toward WCAG 2.1 AA and GIGW 3.0 expectations. This is an
implementation statement, not a certification or a claim of completed assistive-
technology testing.

Implemented controls include:

- a keyboard-accessible skip link to `#main-content`;
- visible `:focus-visible` indicators;
- semantic page landmarks and labelled controls;
- table captions and column-header scopes;
- polite live regions for asynchronous status where applicable;
- English and Hindi interface dictionaries with persisted language preference;
- a fixed high-contrast token palette shared across screens.

Before claiming formal conformance, run automated contrast and accessibility checks,
then complete keyboard-only and screen-reader testing with representative users. Record
the browser, assistive technology, version, test date, failures, and remediations here.
