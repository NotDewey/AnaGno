"""Exact global matching: cardinality, sum IoU, then lexicographic ID pairs.

Successive shortest augmenting paths use rational costs, never rounded floats.
Lexicographic edge preference is an integer bitset secondary cost. This is a
polynomial min-cost flow implementation; intended for bounded page instances.
"""

from fractions import Fraction


def maximum_matching(scores, threshold=0.5):
    """Return sorted (annotation_id, prediction_id, Fraction IoU) triples.

    scores maps (annotation_id, prediction_id) to exact IoU fractions. Only
    edges at or above threshold participate. Empty/zero-overlap edges cannot
    match even if the supplied threshold is zero.
    """
    cutoff = Fraction(str(threshold))
    if not 0 <= cutoff <= 1:
        raise ValueError("IoU threshold must be in 0..1")
    edges = sorted((annotation_id, prediction_id, Fraction(value)) for (annotation_id, prediction_id), value in scores.items()
                   if value > 0 and value >= cutoff)
    if any(not 0 <= value <= 1 for value in scores.values()):
        raise ValueError("invalid IoU")
    if not edges:
        return []
    annotation_ids = sorted({annotation_id for annotation_id, _, _ in edges})
    prediction_ids = sorted({prediction_id for _, prediction_id, _ in edges})
    nodes = 2 + len(annotation_ids) + len(prediction_ids)
    sink = nodes - 1
    graph = [[] for _ in range(nodes)]
    annotation_index = {annotation_id: 1+edge_index for edge_index, annotation_id in enumerate(annotation_ids)}
    prediction_index = {prediction_id: 1+len(annotation_ids)+edge_index for edge_index, prediction_id in enumerate(prediction_ids)}

    def add(source_node, target_node, cost):
        forward = [target_node, len(graph[target_node]), 1, cost]
        reverse = [source_node, len(graph[source_node]), 0, (-cost[0], -cost[1])]
        graph[source_node].append(forward)
        graph[target_node].append(reverse)
        return forward

    for annotation_id in annotation_ids:
        add(0, annotation_index[annotation_id], (Fraction(0), 0))
    for prediction_id in prediction_ids:
        add(prediction_index[prediction_id], sink, (Fraction(0), 0))
    tracked = []
    for rank, (annotation_id, prediction_id, iou) in enumerate(edges):
        edge = add(annotation_index[annotation_id], prediction_index[prediction_id], (-iou, -(1 << (len(edges)-rank-1))))
        tracked.append((annotation_id, prediction_id, iou, edge))
    while True:
        distance = [None] * nodes
        previous = [None] * nodes
        distance[0] = (Fraction(0), 0)
        for _ in range(nodes-1):
            changed = False
            for source_node in range(nodes):
                if distance[source_node] is None:
                    continue
                for edge_index, (target_node, _, capacity, cost) in enumerate(graph[source_node]):
                    if not capacity:
                        continue
                    candidate = (distance[source_node][0]+cost[0], distance[source_node][1]+cost[1])
                    if distance[target_node] is None or candidate < distance[target_node]:
                        distance[target_node], previous[target_node] = candidate, (source_node, edge_index)
                        changed = True
            if not changed:
                break
        if distance[sink] is None:
            break
        target_node = sink
        while target_node != 0:
            source_node, edge_index = previous[target_node]
            edge = graph[source_node][edge_index]
            edge[2] = 0
            graph[target_node][edge[1]][2] = 1
            target_node = source_node
    return [(annotation_id, prediction_id, iou) for annotation_id, prediction_id, iou, edge in tracked if edge[2] == 0]
