import DailyWorkPanel from './DailyWorkPanel';
import SharedAttendancePanel from './SharedAttendancePanel';

export default function DailyWorkWorkspace({ allowNewStart, onChanged, newStartReady }: {
  allowNewStart: boolean; onChanged?: () => void; newStartReady?: () => boolean;
}) {
  return <section className="card mb-4 space-y-4" aria-label="Today's work">
    <h3 className="font-semibold">Today's work</h3>
    <DailyWorkPanel embedded allowNewStart={allowNewStart} onChanged={onChanged} newStartReady={newStartReady}/>
    <SharedAttendancePanel embedded/>
  </section>;
}
