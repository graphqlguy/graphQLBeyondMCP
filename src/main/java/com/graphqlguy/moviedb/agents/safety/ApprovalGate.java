package com.graphqlguy.moviedb.agents.safety;

import com.graphqlguy.moviedb.agents.toolgen.OperationTool;

import java.util.Scanner;

/**
 * The human-in-the-loop checkpoint for writes. Reads are automatic; a mutation
 * pauses the run, shows the human exactly what would execute (the tool, the
 * persisted operation, and the model's arguments), and waits for a yes.
 * <p>
 * Where this gate sits matters as much as what it does. It lives at the tool
 * layer, wrapped around the callback that performs the write, so it holds no
 * matter which loop, framework, or model is driving. A gate implemented in the
 * prompt ("always ask before writing") is a request to the model; a gate
 * implemented at the execution layer is a property of the system.
 */
public class ApprovalGate {

    private final Scanner input = new Scanner(System.in);

    public boolean approve(OperationTool tool, String jsonArguments) {
        return approve(tool.name(), tool.operationDocument(), jsonArguments);
    }

    /** The same contract for callers that hold the pieces instead of the record. */
    public boolean approve(String toolName, String operationDocument, String jsonArguments) {
        System.out.println();
        System.out.println("APPROVAL REQUIRED: the agent wants to run a write.");
        System.out.println("  tool      : " + toolName);
        System.out.println("  operation : " + operationDocument);
        System.out.println("  arguments : " + jsonArguments);
        System.out.print("Execute this mutation? [y/N] ");
        String answer = input.hasNextLine() ? input.nextLine().strip() : "";
        boolean approved = answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
        System.out.println(approved ? "  approved by the human." : "  DENIED by the human.");
        return approved;
    }
}
