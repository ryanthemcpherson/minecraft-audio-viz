interface ConnectCodeProps {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}

const VALID = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ23456789';

function ConnectCode({ value, onChange, disabled }: ConnectCodeProps) {
  const formatDisplay = (raw: string) => {
    const clean = raw.toUpperCase().split('').filter(c => VALID.includes(c)).join('').slice(0, 8);
    if (clean.length > 4) return clean.slice(0, 4) + '-' + clean.slice(4);
    return clean;
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value.replace(/-/g, '').toUpperCase()
      .split('').filter(c => VALID.includes(c)).join('').slice(0, 8);
    onChange(raw);
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/[\s-]/g, '').toUpperCase()
      .split('').filter(c => VALID.includes(c)).join('').slice(0, 8);
    onChange(pasted);
  };

  return (
    <input
      type="text"
      className="input connect-code-input"
      value={formatDisplay(value)}
      onChange={handleChange}
      onPaste={handlePaste}
      placeholder="ABCD-EF12"
      maxLength={9}
      disabled={disabled}
      spellCheck={false}
      autoComplete="off"
    />
  );
}

export default ConnectCode;
