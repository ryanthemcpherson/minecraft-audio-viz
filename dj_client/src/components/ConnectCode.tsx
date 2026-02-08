import { useRef, KeyboardEvent, ClipboardEvent } from 'react';

interface ConnectCodeProps {
  value: string[];
  onChange: (value: string[]) => void;
  label?: string;
}

// Valid characters for connect code
// Word portion (first 4) can have O/I, suffix (last 4) avoids confusables
const VALID_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ23456789';

function ConnectCode({ value, onChange, label = 'Connect Code' }: ConnectCodeProps) {
  const inputRefs = useRef<(HTMLInputElement | null)[]>([]);

  const handleKeyDown = (index: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace') {
      if (value[index] === '' && index > 0) {
        // Move to previous input if current is empty
        inputRefs.current[index - 1]?.focus();
      }
    } else if (e.key === 'ArrowLeft' && index > 0) {
      inputRefs.current[index - 1]?.focus();
    } else if (e.key === 'ArrowRight' && index < 7) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handleChange = (index: number, char: string) => {
    const upperChar = char.toUpperCase();

    // Validate character
    if (char && !VALID_CHARS.includes(upperChar)) {
      return;
    }

    const newValue = [...value];
    newValue[index] = upperChar;
    onChange(newValue);

    // Move to next input if character was entered
    if (upperChar && index < 7) {
      inputRefs.current[index + 1]?.focus();
    }
  };

  const handlePaste = (e: ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pastedText = e.clipboardData.getData('text').toUpperCase();

    // Remove hyphen if present (e.g., BEAT-7K3M)
    const cleanText = pastedText.replace(/-/g, '');

    // Filter to valid characters only
    const validChars = cleanText
      .split('')
      .filter(c => VALID_CHARS.includes(c))
      .slice(0, 8);

    if (validChars.length > 0) {
      const newValue = [...value];
      validChars.forEach((char, i) => {
        if (i < 8) {
          newValue[i] = char;
        }
      });
      onChange(newValue);

      // Focus the next empty input or the last one
      const nextEmpty = validChars.length < 8 ? validChars.length : 7;
      inputRefs.current[nextEmpty]?.focus();
    }
  };

  return (
    <div className="connect-code">
      <label className="input-label">{label}</label>
      <div className="code-inputs">
        <div className="code-group">
          {[0, 1, 2, 3].map(i => (
            <input
              key={i}
              ref={el => (inputRefs.current[i] = el)}
              type="text"
              maxLength={1}
              value={value[i]}
              onChange={e => handleChange(i, e.target.value)}
              onKeyDown={e => handleKeyDown(i, e)}
              onPaste={handlePaste}
              className="code-input"
              autoCapitalize="characters"
              autoComplete="off"
              spellCheck={false}
            />
          ))}
        </div>
        <span className="code-separator">-</span>
        <div className="code-group">
          {[4, 5, 6, 7].map(i => (
            <input
              key={i}
              ref={el => (inputRefs.current[i] = el)}
              type="text"
              maxLength={1}
              value={value[i]}
              onChange={e => handleChange(i, e.target.value)}
              onKeyDown={e => handleKeyDown(i, e)}
              onPaste={handlePaste}
              className="code-input"
              autoCapitalize="characters"
              autoComplete="off"
              spellCheck={false}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

export default ConnectCode;
