# Build and run

Use these guides to go from a working installation to a decision-system change with inspectable outputs and checks. If you are new to
Hotvect, follow the five steps below before choosing an advanced command.

## New to Hotvect

<div class="hv-flow">
  <div class="hv-flow__step"><span>01</span><strong>Install</strong><small>verify one build</small></div>
  <div class="hv-flow__step"><span>02</span><strong>Run example</strong><small>train → inspect</small></div>
  <div class="hv-flow__step"><span>03</span><strong>Learn</strong><small>system concepts</small></div>
  <div class="hv-flow__step"><span>04</span><strong>Choose</strong><small>build or tour</small></div>
  <div class="hv-flow__step"><span>05</span><strong>Validate</strong><small>smallest proof</small></div>
</div>

1. [Install and verify Hotvect](quickstart/index.md).
2. [Run the example product algorithms](first-run/index.md) through training, evaluation, and the browser UI.
3. Read [How Hotvect works](../concepts/how-hotvect-works/index.md).
4. [Build a first algorithm](first-algorithm/index.md), or [tour an existing one](first-workflow/index.md).
5. Use the relevant development, train, backtest, or integration guide for the smallest useful proof.

## Choose by starting point

<div class="grid cards" markdown>

-   **I am modifying an existing algorithm**

    Find its outer definition, trace its factories and children, inspect its data plan, and choose a bounded first run.

    [Tour an existing algorithm](first-workflow/index.md){ .hv-btn }
    [Prepare local development](local-development-env/index.md){ .hv-btn }

-   **I want a complete runnable example**

    Train a scorer, compose Ranker and TopK packages, inspect features, and explore licensed images in the Demo UI.

    [Run the product example](first-run/index.md){ .hv-btn }

-   **I am creating an algorithm**

    Begin with a parameterless ranker. Then follow one complete example when the decision needs learned parameters or
    child algorithms.

    [Build your first algorithm](first-algorithm/index.md){ .hv-btn }
    [Train a model-backed algorithm](first-trainable-algorithm/index.md){ .hv-btn }
    [Compose an algorithm](first-composite-algorithm/index.md){ .hv-btn }
    [Build a TopK algorithm](topk-algorithms/index.md){ .hv-btn }
    [Open the development guide](develop-algorithms/index.md){ .hv-btn }

-   **I need to train or compare locally**

    Generate deterministic runtime data, train one built package, or backtest fixed source revisions; then inspect the
    relevant package and stage outputs.

    [Generate runtime state](state-generation/index.md){ .hv-btn }
    [Train locally](local-train/index.md){ .hv-btn }
    [Backtest locally](local-backtest/index.md){ .hv-btn }
    [Open pipeline stages](pipeline-stages/index.md){ .hv-btn }

-   **I need to understand or debug behavior**

    Use an ordered audit for feature values, equivalence tests for score preservation, and evaluation for quality.

    [Open feature audits](feature-audits/index.md){ .hv-btn }
    [Open validation workflows](validate-and-investigate/index.md){ .hv-btn }

-   **I need composition or artifact reuse**

    Resolve data and child algorithms, patch a definition explicitly, or reuse cached outputs without changing the
    question your run answers.

    [Open data dependencies](patterns/data-dependencies/index.md){ .hv-btn }
    [Open overrides](patterns/override-files/index.md){ .hv-btn }
    [Open parent/child algorithms](patterns/parent-child/index.md){ .hv-btn }

-   **Run remotely**

    Submit an offline workflow to SageMaker when local execution is no longer the right scale.

    [Open remote execution](sagemaker-backtests/index.md){ .hv-btn }

</div>

## Continue by question

- Need a reproducible first run? Use the [example product algorithms](first-run/index.md).
- Need to understand the example implementation? [Study the product algorithms](example-product-algorithms/index.md).
- Need to compare quality or investigate a regression? Go to [Validate and investigate](validate-and-investigate/index.md).
- Need remote execution? Use the [SageMaker backtest guide](sagemaker-backtests/index.md).
- Need to move one candidate from source to the Experiment Management Service (EMS) and online evidence? [Take a change to a live experiment](change-to-live-experiment/index.md).
- Need an exact flag or output path? Use the [CLI reference](../reference/cli/index.md).
