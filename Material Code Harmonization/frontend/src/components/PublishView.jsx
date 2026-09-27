import React, { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../services/api";
import { Award, CheckCircle2, AlertCircle, RefreshCw } from "lucide-react";
import { Table } from "./common/Table";

export function PublishView() {
  const queryClient = useQueryClient();
  const [mintResult, setMintResult] = useState(null);
  const [errorMsg, setErrorMsg] = useState(null);

  const { data: groups = [], isLoading, refetch: reloadGroups } = useQuery({
    queryKey: ["publishableGroups"],
    queryFn: () => api.getPublishableGroups(),
  });

  const mintMutation = useMutation({
    mutationFn: (groupId) => api.publishGroup(groupId),
    onSuccess: (data, groupId) => {
      setMintResult({ groupId, code: data.code });
      setErrorMsg(null);
      queryClient.invalidateQueries(["publishableGroups"]);
      queryClient.invalidateQueries(["dashboardStats"]);
      queryClient.invalidateQueries(["catalog"]);
    },
    onError: (err) => {
      setErrorMsg(err.message || "Failed to publish and mint code.");
    },
  });

  const handleMint = (groupId) => {
    setErrorMsg(null);
    setMintResult(null);
    mintMutation.mutate(groupId);
  };

  return (
    <div className="publish-view page-container">
      <header className="page-header">
        <div className="flex items-center justify-between w-full">
          <div>
            <h1 className="page-title">National Catalog Publication & Code Minting</h1>
            <p className="page-subtitle">
              Senior Reviewer sign-off queue (Step 2 of Governance Loop). Groups with verified mappings awaiting authoritative NUMM national code minting.
            </p>
          </div>
          <button
            onClick={() => reloadGroups()}
            className="btn btn-secondary"
          >
            <RefreshCw size={14} /> Refresh Queue
          </button>
        </div>
      </header>

      {mintResult && (
        <div className="alert badge-success mb-4" aria-live="polite">
          <CheckCircle2 size={16} aria-hidden="true" />
          <div>
            <h4>
              National Material Code Minted Successfully
            </h4>
            <p>
              Canonical Code: <strong className="font-mono">{mintResult.code}</strong> (ISO 7064 checksum verified). Item is now published to the National Catalog.
            </p>
          </div>
        </div>
      )}

      {errorMsg && (
        <div className="alert alert-danger mb-4" role="alert">
          <AlertCircle size={16} />
          <span>{errorMsg}</span>
        </div>
      )}

      {isLoading ? (
        <div className="empty-state" role="status" aria-live="polite">
          Loading publication queue...
        </div>
      ) : groups.length === 0 ? (
        <div className="empty-state card">
          <Award size={16} className="empty-state-icon" aria-hidden="true" />
          <h3 className="empty-state-title">
            No Groups Pending Publication
          </h3>
          <p className="empty-state-sub">
            All technical review decisions have been published, or pending items require domain reviewer sign-off first.
          </p>
        </div>
      ) : (
        <Table caption="Groups awaiting national code publication">
            <thead>
              <tr><th scope="col">Provisional Ref</th><th scope="col">Commodity Category</th>
                <th scope="col">Standardized Description</th><th scope="col">Confirmed Items</th>
                <th scope="col">CPSE Coverage</th><th scope="col" className="text-right">Action</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((group) => (
                <tr key={group.groupId}>
                  <td className="table-code">
                    {group.provisionalRef}
                  </td>
                  <td><span className="badge badge-neutral">
                      {group.categoryName}
                    </span>
                  </td>
                  <td className="font-medium">
                    {group.standardizedDescription}
                  </td>
                  <td><span className="text-success font-semibold">{group.confirmedMappingCount} verified</span>
                  </td>
                  <td><span className="text-muted">{group.distinctCpseCount} CPSE(s)</span>
                  </td>
                  <td className="text-right">
                    <button
                      onClick={() => handleMint(group.groupId)}
                      disabled={mintMutation.isPending}
                      className="btn btn-primary"
                    >
                      {mintMutation.isPending && mintMutation.variables === group.groupId ? "Minting..." : "Publish & Mint Code"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
        </Table>
      )}
    </div>
  );
}
