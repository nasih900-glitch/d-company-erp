export const DCOMPANY_APP_MARK_PATH = '/brand/dcompany-app-mark.png';

export default function DCompanyBrandMark({
  className = 'h-11 w-11',
}: {
  className?: string;
}) {
  return (
    <span
      aria-hidden="true"
      className={`${className} inline-grid shrink-0 place-items-center overflow-hidden rounded-full bg-black ring-1 ring-accent-gold/50`}
    >
      <img
        src={DCOMPANY_APP_MARK_PATH}
        alt=""
        className="h-full w-full scale-150 object-contain"
      />
    </span>
  );
}
