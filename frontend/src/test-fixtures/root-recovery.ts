export class TestBroadcastChannel {
  static peers = new Set<TestBroadcastChannel>();
  onmessage: ((event: MessageEvent) => void) | null = null;
  constructor(readonly name: string) { TestBroadcastChannel.peers.add(this); }
  postMessage(data: unknown) {
    for (const other of TestBroadcastChannel.peers) if (other !== this && other.name === this.name) other.onmessage?.({ data } as MessageEvent);
  }
  close() { TestBroadcastChannel.peers.delete(this); }
}
