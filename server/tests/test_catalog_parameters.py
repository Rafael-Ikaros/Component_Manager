from app.admin.data import _component_search_text, _normalize_search_term
from app.part_lookup import _extract_product_parameters


def test_missing_parameters_are_not_inferred_from_model():
    assert _extract_product_parameters({"productModel": "0603B104K500NT"}) == {}


def test_schema_product_properties_keep_explicit_values():
    result = _extract_product_parameters({"additionalProperty": [
        {"name": "Capacitance", "value": "100nF"},
        {"name": "Voltage Rating", "value": "50V"},
    ]})
    assert result == {"Capacitance": "100nF", "Voltage Rating": "50V"}


def test_parameter_aliases_are_searchable_without_changing_name():
    index = _component_search_text(
        "FOJAN FRH0603B1002TS", "参数·Resistance：10kΩ\n参数：额定电压(V)：50",
    )
    for term in ("阻值10kohm", "resistance10kΩ", "耐压50V", "ratedvoltage50V"):
        assert _normalize_search_term(term) in index
    assert _normalize_search_term("耐压500V") not in index
