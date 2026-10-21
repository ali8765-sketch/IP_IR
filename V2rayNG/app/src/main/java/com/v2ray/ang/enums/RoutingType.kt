package com.v2ray.ang.enums

enum class RoutingType(val fileName: String) {
    WHITE("custom_routing_white"),
    BLACK("custom_routing_black"),
    GLOBAL("custom_routing_global"),
    WHITE_IRAN("custom_routing_white_iran"),
    WHITE_RUSSIA("custom_routing_white_russia"),

    /** Chapar: everything through the proxy; only local network addresses direct (see ChaparRouting). */
    CHAPAR("custom_routing_chapar")
}
