"""Dictionary contracts for Hotvect training artifacts."""

from typing import Any, NotRequired, Required, TypedDict


class InputDataSpec(TypedDict):
    """Contract for a materialized data input and its optional remote location.

    Attributes:
        data_prefix: Input directory name under the configured data base path.
        s3_uri: Optional S3 location or locations keyed by data environment.
    """

    data_prefix: Required[str]
    s3_uri: NotRequired[str | dict[str, str]]


class PredictionDataSpec(InputDataSpec):
    """Contract for dated prediction inputs and their output location.

    Attributes:
        number_of_days: Number of dated input partitions.
        lag_days: Gap between the test date and the newest input partition.
        output_uri: Output URI template or templates keyed by data environment.
    """

    number_of_days: Required[int]
    lag_days: Required[int]
    output_uri: Required[str | dict[str, str]]


class StateSourceDataSpec(InputDataSpec):
    """Contract for a static or date-partitioned state source.

    A dated source supplies both number_of_days and lag_days. A static source
    omits both. Training windows are declared on the trainable algorithm itself.

    Attributes:
        number_of_days: Optional number of dated source partitions.
        lag_days: Optional gap between the test date and the newest source partition.
    """

    number_of_days: NotRequired[int]
    lag_days: NotRequired[int]


class AlgorithmDefinition(TypedDict):
    """Shared parent contract for Hotvect algorithm definitions.

    Role-specific fields belong to the subclasses below. These DTOs describe
    selected fields; all other JSON fields remain in the underlying dictionary.

    Attributes:
        algorithm_name: Name of the algorithm.
        algorithm_version: Version of the algorithm artifact.
        algorithm_factory_classname: Factory used to construct the runtime algorithm.
        hyperparameter_version: Optional hyperparameter version; it is part of the artifact directory name.
        hotvect_version: Optional Hotvect framework version provenance.
        git_describe: Optional source revision provenance.
        hotvect_execution_parameters: Optional pipeline execution settings.
        training_container: Optional legacy image declaration for SageMaker execution.
        sagemaker_training_job_definition: Optional SageMaker job configuration.
        sagemaker_execution_parameters: Optional legacy SageMaker configuration.
        dependencies: Optional direct dependency names or mappings to partial definition overrides.
    """

    algorithm_name: Required[str]
    algorithm_version: Required[str]
    algorithm_factory_classname: Required[str]
    hyperparameter_version: NotRequired[str]
    hotvect_version: NotRequired[str]
    git_describe: NotRequired[str]
    hotvect_execution_parameters: NotRequired[dict[str, Any]]
    training_container: NotRequired[str]
    sagemaker_training_job_definition: NotRequired[dict[str, Any]]
    sagemaker_execution_parameters: NotRequired[dict[str, Any]]
    dependencies: NotRequired[dict[str, dict[str, Any]] | list[str]]


class TrainableAlgorithmDefinition(AlgorithmDefinition):
    """Contract for an algorithm trained through a training command.

    Training-window and input fields are optional because the pipeline supports
    default windows and input locations. Data prefixes belong to the input spec.

    Attributes:
        training_command: Jinja2 command template executed by the train stage.
        number_of_training_days: Number of dated training partitions.
        training_lag_days: Gap between the test date and the newest training partition.
        train_data_spec: Optional training input declaration.
        train_data_prefix: Optional legacy training input prefix.
        test_data_spec: Optional evaluation input declaration.
        test_data_prefix: Optional legacy evaluation input prefix.
        train_decoder_parameters: Optional training decoder settings.
        prediction_spec: Optional prediction input and output declaration.
    """

    training_command: Required[str]
    number_of_training_days: NotRequired[int]
    training_lag_days: NotRequired[int]
    train_data_spec: NotRequired[InputDataSpec]
    train_data_prefix: NotRequired[str]
    test_data_spec: NotRequired[InputDataSpec]
    test_data_prefix: NotRequired[str]
    train_decoder_parameters: NotRequired[dict[str, Any]]
    prediction_spec: NotRequired[PredictionDataSpec]


class StateAlgorithmDefinition(AlgorithmDefinition):
    """Contract for an algorithm that generates state from named source inputs.

    Attributes:
        generator_factory_classname: Factory used to construct the state generator.
        source_data: Source names mapped to their input declarations.
        state_output_filename: Optional relative path to the generated state. When
            omitted, the algorithm's parameter-source directory is the state root.
    """

    generator_factory_classname: Required[str]
    source_data: Required[dict[str, StateSourceDataSpec]]
    state_output_filename: NotRequired[str]


class DependencyStateMetadata(TypedDict, total=False):
    """Exact generate-state metadata materialized for one direct dependency.

    Both fields are absent when the dependency has no generate-state metadata.

    Attributes:
        generate_state_metadata_path: Path to the dependency's metadata JSON file.
        generate_state_metadata: Complete JSON object read from that file.
    """

    generate_state_metadata_path: str
    generate_state_metadata: dict[str, Any]
