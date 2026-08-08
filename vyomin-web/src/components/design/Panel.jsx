const CORNER_SIZE = 14;

function Corner({ position }) {
  const base = 'absolute h-[14px] w-[14px] border-[var(--hairline)] transition-colors duration-200 group-hover:border-[var(--accent)]';
  const styles = {
    tl: `${base} top-0 left-0 border-t-2 border-l-2`,
    tr: `${base} top-0 right-0 border-t-2 border-r-2`,
    bl: `${base} bottom-0 left-0 border-b-2 border-l-2`,
    br: `${base} bottom-0 right-0 border-b-2 border-r-2`,
  };
  return <span className={styles[position]} style={{ width: CORNER_SIZE, height: CORNER_SIZE }} />;
}

export function Panel({ as: Tag = 'div', className = '', children, ...props }) {
  return (
    <Tag
      className={`group relative border border-[var(--hairline)] bg-[var(--panel)] ${className}`}
      {...props}
    >
      <Corner position="tl" />
      <Corner position="tr" />
      <Corner position="bl" />
      <Corner position="br" />
      {children}
    </Tag>
  );
}
