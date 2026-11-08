"""Decimal strings shared by inventory snapshots and inventory operations."""
from decimal import Decimal, ROUND_HALF_EVEN, localcontext
import re


def decimal_text(value: str | None, places: int, *, signed: bool = False) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not re.fullmatch(r"-?\d+(?:\.\d+)?", value):
        raise ValueError("Cost must be a plain decimal string.")
    amount = Decimal(value)
    if (not signed and amount < 0) or max(0, -amount.as_tuple().exponent) > places:
        raise ValueError(f"Cost must be non-negative and have at most {places} decimal places." if not signed else f"Cost supports at most {places} decimal places.")
    return amount_text(amount)


def amount_text(value: Decimal) -> str:
    if value == 0:
        return "0"
    result = format(value, "f")
    return (result.rstrip("0").rstrip(".") if "." in result else result) or "0"


def movement_cost(old_quantity: int, unit_cost: str | None, quantity: int,
                  purchase_amount: str | None, inbound: bool) -> tuple[str | None, str | None]:
    values = [Decimal(value) for value in (unit_cost, purchase_amount) if value is not None]
    with localcontext() as context:
        context.prec = max([64, *[len(value.as_tuple().digits) + 40 for value in values]])
        if not inbound:
            cost = amount_text(Decimal(unit_cost) * quantity) if unit_cost is not None else None
            return (unit_cost if old_quantity != quantity else None), cost
        if purchase_amount is None or (old_quantity and unit_cost is None):
            return None, purchase_amount
        total = Decimal(purchase_amount) + (Decimal(unit_cost) * old_quantity if old_quantity else 0)
        unit = (total / (old_quantity + quantity)).quantize(Decimal("0.0000000000000001"), rounding=ROUND_HALF_EVEN)
        return amount_text(unit), purchase_amount


def backfill_cost(quantity: int, previous_unit: str | None, total: str) -> tuple[str, str]:
    unit, _ = movement_cost(0, None, quantity, total, True)
    with localcontext() as context:
        context.prec = max(64, len(total) + len(previous_unit or "") + 40)
        delta = Decimal(total) - (Decimal(previous_unit) * quantity if previous_unit is not None else 0)
        return unit, amount_text(delta)
