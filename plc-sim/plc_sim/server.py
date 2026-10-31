"""Runs the simulated S7-1200: the machine model plus the CPU's two interfaces, OPC UA and S7 (PUT/GET).

Both interfaces serve the same DB image, updated every scan, so a gateway can be tested with either protocol.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal

from asyncua import Server, ua
from asyncua.server.user_managers import UserManager
from asyncua.crypto.permission_rules import User, UserRole
import snap7
from snap7.type import SrvArea

from . import layout
from .machine import Machine

log = logging.getLogger("plc-sim")

_UA_TYPES = {
    "BOOL": ua.VariantType.Boolean,
    "INT": ua.VariantType.Int16,
    "DINT": ua.VariantType.Int32,
    "REAL": ua.VariantType.Float,
}


class _PasswordUsers(UserManager):
    """Accepts one user name and password, like a CPU with "user authentication" enabled."""

    def __init__(self, user: str, password: str) -> None:
        self._user = user
        self._password = password

    def get_user(self, iserver, username=None, password=None, certificate=None):
        if username == self._user and password == self._password:
            return User(role=UserRole.User)
        return None


async def run(args: argparse.Namespace) -> None:
    machine = Machine(seed=args.seed)
    db = bytearray(layout.DB_SIZE)
    layout.write(db, machine.plc_values())

    s7 = snap7.server.Server(log=False)
    s7.register_area(SrvArea.DB, layout.DB_NUMBER, db)
    s7.start(tcp_port=args.s7_port)
    log.info("S7 server on port %d, DB%d with %d bytes", args.s7_port, layout.DB_NUMBER, layout.DB_SIZE)

    user_manager = _PasswordUsers(args.opcua_user, args.opcua_password) if args.opcua_user else None
    server = Server(user_manager=user_manager)
    await server.init()
    server.set_endpoint(f"opc.tcp://0.0.0.0:{args.opcua_port}")
    server.set_server_name(f"Simulated S7-1200 ({args.name})")
    server.set_security_policy([ua.SecurityPolicyType.NoSecurity])
    server.set_identity_tokens([ua.UserNameIdentityToken] if user_manager else [ua.AnonymousIdentityToken])
    # index 2 is taken by the server itself in asyncua; the gateway resolves the URI, never the index
    ns = await server.register_namespace(layout.SIEMENS_NAMESPACE_URI)
    block = await server.nodes.objects.add_object(ua.NodeId(f'"{layout.DB_NAME}"', ns), layout.DB_NAME)
    nodes = {}
    for var in layout.VARIABLES:
        initial = layout.read(db)[var.name]
        node = await block.add_variable(
            ua.NodeId(var.node_id(), ns), var.name, ua.Variant(initial, _UA_TYPES[var.s7_type])
        )
        nodes[var.name] = node

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    async with server:
        log.info("OPC UA server on opc.tcp://0.0.0.0:%d, namespace %s", args.opcua_port, layout.SIEMENS_NAMESPACE_URI)
        scan = args.scan_ms / 1000.0
        last = loop.time()
        last_state = None
        while not stop.is_set():
            now = loop.time()
            machine.step(now - last)
            last = now
            values = machine.plc_values()
            s7.lock_area(SrvArea.DB, layout.DB_NUMBER)
            try:
                layout.write(db, values)
            finally:
                s7.unlock_area(SrvArea.DB, layout.DB_NUMBER)
            for name, value in values.items():
                await nodes[name].write_value(ua.Variant(value, _UA_TYPES[layout.BY_NAME[name].s7_type]))
            if machine.state != last_state:
                log.info("state %s fault=%d good=%d reject=%d", machine.state.value, machine.fault_code,
                         machine.good_count, machine.reject_count)
                last_state = machine.state
            try:
                await asyncio.wait_for(stop.wait(), timeout=scan)
            except TimeoutError:
                pass
    s7.stop()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default=os.getenv("PLC_NAME", "ENS-01"))
    parser.add_argument("--seed", type=int, default=int(os.getenv("PLC_SEED", "1")))
    parser.add_argument("--opcua-port", type=int, default=int(os.getenv("PLC_OPCUA_PORT", "4840")))
    parser.add_argument("--s7-port", type=int, default=int(os.getenv("PLC_S7_PORT", "102")))
    parser.add_argument("--opcua-user", default=os.getenv("PLC_OPCUA_USER", ""))
    parser.add_argument("--opcua-password", default=os.getenv("PLC_OPCUA_PASSWORD", ""))
    parser.add_argument("--scan-ms", type=int, default=int(os.getenv("PLC_SCAN_MS", "100")))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("asyncua").setLevel(logging.WARNING)
    logging.getLogger("snap7").setLevel(logging.WARNING)
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
