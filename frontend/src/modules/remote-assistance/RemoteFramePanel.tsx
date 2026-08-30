import {
  AlertTriangle,
  Clock3,
  EyeOff,
  Loader2,
  LockKeyhole,
  MonitorSmartphone,
  WifiOff,
  type LucideIcon,
} from 'lucide-react';

import type {
  RemoteAssistanceDeviceDTO,
  RemoteAssistanceSessionDTO,
} from '@/lib/erp-api';
import { deviceName } from './remote-assistance-presentation';
import type { RemoteFrameViewModel } from './remote-assistance-state';

const TIME_ONLY = new Intl.DateTimeFormat(undefined, {
  hour: 'numeric',
  minute: '2-digit',
  second: '2-digit',
});

export function RemoteFramePanel({
  device,
  session,
  frame,
}: {
  device: RemoteAssistanceDeviceDTO;
  session: RemoteAssistanceSessionDTO | null;
  frame: RemoteFrameViewModel;
}) {
  const frameStatus = framePresentation(frame, device, session);
  const hasImage = Boolean(frame.src);

  return (
    <section className="overflow-hidden rounded-xl border border-bg-border bg-bg/85" aria-label="ERP-only remote view">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-bg-border px-3 py-2.5 text-xs">
        <div className="flex items-center gap-2 font-semibold">
          <MonitorSmartphone className="text-accent" size={15} aria-hidden="true" />
          ERP-only view
        </div>
        <div className="flex items-center gap-2 text-fg-muted">
          <span className={`h-2 w-2 rounded-full ${frameStatus.dotClass}`} aria-hidden="true" />
          {frameStatus.label}
          {frame.receivedAt ? ` · ${TIME_ONLY.format(new Date(frame.receivedAt))}` : ''}
        </div>
      </div>
      <div className="relative grid min-h-[280px] place-items-center overflow-hidden bg-[#020202] md:min-h-[360px]">
        {hasImage ? (
          <img
            src={frame.src ?? undefined}
            alt={`Redacted ERP screen from ${deviceName(device)}`}
            className={`max-h-[62dvh] w-full object-contain ${frame.state === 'fresh' ? '' : 'opacity-45'}`}
          />
        ) : null}
        {frame.state === 'loading' ? (
          <FramePlaceholder
            Icon={Loader2}
            iconClass="animate-spin text-accent"
            title="Waiting for the latest ERP frame"
            detail="The tablet is preparing a redacted app-only view."
          />
        ) : null}
        {!hasImage && frame.state !== 'loading' ? (
          <FramePlaceholder
            Icon={frameStatus.Icon}
            iconClass={frameStatus.iconClass}
            title={frameStatus.title}
            detail={frameStatus.detail}
          />
        ) : null}
        {hasImage && frame.state !== 'fresh' ? (
          <div className="absolute inset-0 grid place-items-center bg-bg/55 p-4">
            <div className="max-w-md rounded-xl border border-accent-gold/45 bg-bg-surface/95 px-4 py-3 text-center shadow-glow">
              <frameStatus.Icon className={`mx-auto ${frameStatus.iconClass}`} size={21} aria-hidden="true" />
              <p className="mt-2 text-sm font-semibold">{frameStatus.title}</p>
              <p className="mt-1 text-xs leading-5 text-fg-muted">{frameStatus.detail}</p>
            </div>
          </div>
        ) : null}
        {hasImage && frame.state === 'fresh' ? (
          <div className="pointer-events-none absolute inset-x-3 bottom-3 flex justify-center">
            <div className="flex items-center gap-2 rounded-lg border border-accent/35 bg-bg-surface/95 px-3 py-2 text-xs shadow-glow">
              <LockKeyhole className="text-accent" size={14} aria-hidden="true" />
              <span className="font-semibold">Sensitive fields are hidden</span>
              <span className="text-fg-muted">· redacted on the tablet</span>
            </div>
          </div>
        ) : null}
      </div>
      <div className="flex flex-col gap-1 border-t border-bg-border px-3 py-2 text-[11px] text-fg-subtle sm:flex-row sm:items-center sm:justify-between">
        <span>No camera, other apps, notifications, raw taps or keyboard access.</span>
        {frame.width && frame.height ? <span>{frame.width} × {frame.height}</span> : null}
      </div>
    </section>
  );
}


function FramePlaceholder({
  Icon,
  iconClass,
  title,
  detail,
}: {
  Icon: LucideIcon;
  iconClass: string;
  title: string;
  detail: string;
}) {
  return (
    <div className="max-w-md px-6 py-10 text-center">
      <div className="mx-auto grid h-12 w-12 place-items-center rounded-xl border border-bg-border bg-bg-raised/70">
        <Icon className={iconClass} size={22} aria-hidden="true" />
      </div>
      <p className="mt-4 font-semibold">{title}</p>
      <p className="mt-1 text-sm leading-6 text-fg-muted">{detail}</p>
    </div>
  );
}


function framePresentation(
  frame: RemoteFrameViewModel,
  device: RemoteAssistanceDeviceDTO,
  session: RemoteAssistanceSessionDTO | null,
): {
  label: string;
  title: string;
  detail: string;
  Icon: LucideIcon;
  iconClass: string;
  dotClass: string;
} {
  if (!session || session.status !== 'active') {
    return {
      label: 'Private',
      title: 'No active assistance session',
      detail: 'A live ERP frame appears only after employee approval and a short-lived session start.',
      Icon: EyeOff,
      iconClass: 'text-fg-muted',
      dotClass: 'bg-fg-subtle',
    };
  }
  if (!device.is_remote_online || frame.state === 'offline') {
    return {
      label: 'Offline',
      title: 'Tablet is offline',
      detail: frame.message || 'The last received redacted ERP frame remains on this screen temporarily, but it is not live.',
      Icon: WifiOff,
      iconClass: 'text-accent-gold',
      dotClass: 'bg-accent-gold',
    };
  }
  if (frame.state === 'privacy') {
    return {
      label: 'Privacy protected',
      title: 'No ERP frame is available',
      detail: frame.message || 'Screen sharing may be paused, protected, or awaiting employee consent.',
      Icon: LockKeyhole,
      iconClass: 'text-accent',
      dotClass: 'bg-accent',
    };
  }
  if (frame.state === 'stale') {
    return {
      label: 'Last received frame (stale)',
      title: 'This ERP frame is stale',
      detail: frame.message || 'The last received redacted ERP frame remains on this screen temporarily. Do not treat it as the current tablet screen.',
      Icon: Clock3,
      iconClass: 'text-accent-gold',
      dotClass: 'bg-accent-gold',
    };
  }
  if (frame.state === 'error') {
    return {
      label: 'View unavailable',
      title: 'The ERP view could not refresh',
      detail: frame.message || 'The last received frame remains on this screen temporarily while the next frame is requested.',
      Icon: AlertTriangle,
      iconClass: 'text-accent-bad',
      dotClass: 'bg-accent-bad',
    };
  }
  return {
    label: frame.state === 'loading' ? 'Connecting' : 'Live redacted frame',
    title: 'ERP-only view is live',
    detail: 'Sensitive fields are hidden on the tablet before this frame is sent.',
    Icon: LockKeyhole,
    iconClass: 'text-accent-good',
    dotClass: frame.state === 'loading' ? 'bg-accent-gold' : 'bg-accent-good',
  };
}
