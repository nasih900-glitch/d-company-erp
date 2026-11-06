from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
API_CLIENT = (
    ROOT
    / "android-native"
    / "app"
    / "src"
    / "main"
    / "java"
    / "cloud"
    / "dcompany"
    / "erp"
    / "core"
    / "net"
    / "ApiClient.kt"
)


def test_ordinary_and_remote_clients_share_one_session_refresh_coordinator() -> None:
    source = API_CLIENT.read_text()
    init = source.split("fun init(", 1)[1].split(
        "internal fun activateTerminalScope", 1
    )[0]
    factory = source.split(
        "internal fun <T> createApiWithNetworkProof", 1
    )[1].split("private fun authenticatedClientBuilder", 1)[0]
    ordinary = source.split(
        "private fun authenticatedClientBuilder", 1
    )[1].split("private fun remoteAuthenticationInterceptors", 1)[0]
    remote = source.split(
        "private fun remoteAuthenticationInterceptors", 1
    )[1].split("internal fun remoteProofClientBuilder", 1)[0]
    proof_builder = source.split(
        "internal fun remoteProofClientBuilder", 1
    )[1].split("private class AuthenticatedSession", 1)[0]
    interceptor = source.split("class AuthInterceptor", 1)[1].split(
        "internal class PricingTokenInterceptor", 1
    )[0]

    assert source.count("SessionRefreshCoordinator(") == 1
    assert "private lateinit var refreshApi" not in source
    assert "val sessionRefreshApi = Retrofit.Builder()" in init
    assert "val sessionRefreshCoordinator = SessionRefreshCoordinator(" in init
    coordinator_wiring = init.split(
        "val sessionRefreshCoordinator = SessionRefreshCoordinator(", 1
    )[1].split("val client = authenticatedClientBuilder()", 1)[0]
    assert "tokenStore = tokenStore" in coordinator_wiring
    assert "sessionRefreshApi.refresh(RefreshRequest(refresh))" in coordinator_wiring
    assert "AuthenticatedSession(tokenStore, sessionRefreshCoordinator)" in init
    assert "authenticatedSession = session" in init
    assert "val client = authenticatedClientBuilder()" in init
    assert "remoteProofClientBuilder(proofInterceptor, operationGate, remoteAuthenticationInterceptors()).build()" in factory
    assert ".callFactory(tagged(client, binding = null))" in factory
    assert "authentication.forEach { addInterceptor(it) }" in proof_builder
    for builder in (ordinary, remote):
        assert "val session = authenticatedSession" in builder
        assert "AuthInterceptor(" in builder
        assert "tokenStore = session.tokenStore" in builder
        assert "refreshCoordinator = session.refreshCoordinator" in builder

    assert "internal class AuthInterceptor" in source
    assert "private val tokenStore: TokenStore" in interceptor
    assert "private val refreshCoordinator: SessionRefreshCoordinator" in interceptor
    assert "val captured = binding?.loginLease ?: boot?.lease" in interceptor
    assert "val lease = if (captured != null) tokenStore.refreshLeaseFor(captured)" in interceptor
    assert "else if (boot != null) null else tokenStore.refreshLease()" in interceptor
    assert "refreshCoordinator.refresh(lease)" in interceptor
    assert "SessionRefreshCoordinator(" not in interceptor
