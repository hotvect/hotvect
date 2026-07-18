from typing import NamedTuple


GSO_CREATOR = "Google Research"
GSO_DATASET = "Google Scanned Objects"
GSO_DATASET_URL = "https://research.google/resources/datasets/scanned-objects-google-research/"
GSO_FUEL_ORIGIN = "https://fuel.gazebosim.org"
GSO_LICENSE_NAME = "Creative Commons Attribution 4.0 International"
GSO_LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"


class ProductSpec(NamedTuple):
    action_id: str
    title: str
    category: str
    price: float
    popularity: float
    novelty: float
    image_filename: str
    gso_model_id: str
    image_sha256: str
    training_queries: tuple[str, str]
    test_query: str


PRODUCTS = (
    ProductSpec(
        "construction-kit",
        "Build and bolt construction kit",
        "building",
        29.90,
        0.81,
        0.44,
        "construction-kit.jpg",
        "30_CONSTRUCTION_SET",
        "15686761bff36473422fc6e487f86e87e811f1ba10f88630d16f6b170dd2609b",
        ("construction kit", "build and bolt"),
        "bolt building kit",
    ),
    ProductSpec(
        "wooden-blocks",
        "Natural wooden building blocks",
        "building",
        24.50,
        0.76,
        0.31,
        "wooden-blocks.jpg",
        "50_BLOCKS",
        "a1c62b01015bd36a8bc6ca4f8f32fa90048264c5943bdb6ce13b66b66c3df4da",
        ("wooden blocks", "natural building blocks"),
        "wooden building set",
    ),
    ProductSpec(
        "vegetable-play-set",
        "Wooden vegetable play set",
        "pretend-play",
        18.90,
        0.68,
        0.57,
        "vegetable-play-set.jpg",
        "ASSORTED_VEGETABLE_SET",
        "b7c20bcb3b17ebc3d72c037c3a9901e507fee2592b4442fc8fdf79bd121c233b",
        ("vegetable play set", "wooden vegetables"),
        "pretend vegetables",
    ),
    ProductSpec(
        "rainbow-pebble-stacker",
        "Rainbow pebble stacking set",
        "stacking",
        21.00,
        0.73,
        0.66,
        "rainbow-pebble-stacker.jpg",
        "BABY_CAR",
        "1b9557033f319366a02cdb04f758ed0c67d9ece2b07bb0d338a5d965cd32026d",
        ("rainbow pebble stacker", "pebble stacking set"),
        "colorful stacking pebbles",
    ),
    ProductSpec(
        "balancing-cactus",
        "Balancing cactus game",
        "balancing",
        27.50,
        0.64,
        0.72,
        "balancing-cactus.jpg",
        "BALANCING_CACTUS",
        "1ba0f19396dea076989e07a7690831921e2ebec3bbb9405e18cc5f55fa8faaa2",
        ("balancing cactus", "cactus balance game"),
        "wooden cactus game",
    ),
    ProductSpec(
        "castle-play-set",
        "Wooden castle play set",
        "building",
        34.00,
        0.59,
        0.69,
        "castle-play-set.jpg",
        "CASTLE_BLOCKS",
        "5f17c452605a52e0af5aedbfb071594f3dde96dbe987f69df1908618f8281607",
        ("wooden castle", "castle play set"),
        "build a wooden castle",
    ),
    ProductSpec(
        "rainbow-cone-sorter",
        "Rainbow cone sorting toy",
        "stacking",
        16.50,
        0.78,
        0.38,
        "rainbow-cone-sorter.jpg",
        "CONE_SORTING",
        "1c3dd6e6826e29e2eef34996d695e5583f006cfa90cbfa3aebc7a66717d3e7d6",
        ("rainbow cone sorter", "cone sorting toy"),
        "stacking cones",
    ),
    ProductSpec(
        "geometric-sorting-board",
        "Geometric shape sorting board",
        "sorting",
        22.90,
        0.84,
        0.35,
        "geometric-sorting-board.jpg",
        "GEOMETRIC_SORTING_BOARD",
        "4a5eaf189cc856e83e51ac7505320c95a4783ae086441e0437cab443dac743c0",
        ("geometric sorting board", "shape sorting board"),
        "geometric shape sorter",
    ),
    ProductSpec(
        "toy-helicopter",
        "Wooden toy helicopter",
        "vehicles",
        19.90,
        0.71,
        0.52,
        "toy-helicopter.jpg",
        "HELICOPTER",
        "0d1890dcd0650c7b020bfcd44c40ea11ff241432b1c5858fdec4d0208abd724b",
        ("toy helicopter", "wooden helicopter"),
        "helicopter vehicle toy",
    ),
    ProductSpec(
        "toy-excavator",
        "Blue toy excavator",
        "vehicles",
        25.90,
        0.82,
        0.41,
        "toy-excavator.jpg",
        "MINI_EXCAVATOR",
        "0180d277422ba24288fa9f60084f326db6b8851fa34d81b6eea5ede39c7ae0f9",
        ("toy excavator", "blue excavator"),
        "construction vehicle excavator",
    ),
    ProductSpec(
        "owl-shape-sorter",
        "Owl shape sorting toy",
        "sorting",
        20.50,
        0.69,
        0.74,
        "owl-shape-sorter.jpg",
        "OWL_SORTER",
        "c98791fc20ff509e6589c4417f65b9daade3285e767e9b25e5a05724945b1db2",
        ("owl shape sorter", "owl sorting toy"),
        "colorful owl sorter",
    ),
    ProductSpec(
        "wooden-tea-set",
        "Wooden tea party set",
        "pretend-play",
        31.50,
        0.75,
        0.63,
        "wooden-tea-set.jpg",
        "TEA_SET",
        "70ba7bcc448428d323207493a16a29e32f09d2f15500c16e0140abef86f14d0f",
        ("wooden tea set", "tea party set"),
        "pretend tea party",
    ),
)


def model_url(product: ProductSpec) -> str:
    return f"{GSO_FUEL_ORIGIN}/1.0/GoogleResearch/models/{product.gso_model_id}"


def image_url(product: ProductSpec) -> str:
    return f"{model_url(product)}/tip/files/thumbnails/0.jpg"
